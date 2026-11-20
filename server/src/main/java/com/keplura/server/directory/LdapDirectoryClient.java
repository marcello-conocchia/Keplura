package com.keplura.server.directory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.StartTlsRequest;
import javax.naming.ldap.StartTlsResponse;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ADR-0012 §2 — LDAP/AD-Zugriff über das JDK (JNDI), ohne Zusatzbibliothek.
 *
 * <p>Sicherheitsregeln: nur {@code ldaps://} oder {@code ldap://} mit StartTLS
 * (Klartext nur mit ausdrücklicher Admin-Option); Hostname-Prüfung bleibt an
 * (JDK-Standard); leere Passwörter werden IMMER abgelehnt (LDAP würde sie als
 * anonyme Anmeldung akzeptieren); jede Benutzereingabe wird für Filter nach
 * RFC 4515 maskiert; Referrals werden ignoriert; feste Zeitlimits.
 */
@Component
public class LdapDirectoryClient {

    /** AD returns referrals to separate naming contexts at a domain root.
     * They remain outside this configured directory; never follow them. */
    static boolean hasMoreLocal(NamingEnumeration<?> entries) throws NamingException {
        try {
            return entries.hasMore();
        } catch (javax.naming.PartialResultException ignoredReferral) {
            return false;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(LdapDirectoryClient.class);
    static final int MAX_GROUPS = 1000;
    static final int NESTED_DEPTH = 5;

    /** Resolved directory user. principals = canonical keys (sid:…, posix-uid:…, ldap-dn:…). */
    public record DirectoryUser(String dn, String login, String displayName, String email, Set<String> principals,
                                boolean disabled) {
    }

    public static class DirectoryException extends Exception {
        public DirectoryException(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------- public API

    /** Service-Bind + eine Probe-Suche; liefert die Anzahl gefundener Benutzer (max. 1000). */
    public int testConnection(DirectoryConnection c, String bindPassword) throws DirectoryException {
        LdapContext ctx = open(c, c.getBindDn(), bindPassword);
        try {
            SearchControls sc = controls(new String[]{"cn"});
            sc.setCountLimit(1000);
            String filter = c.getUserFilter().replace("{login}", "*");
            int n = 0;
            NamingEnumeration<SearchResult> it = ctx.search(c.getBaseDn(), filter, sc);
            try {
                while (hasMoreLocal(it)) {
                    it.next();
                    n++;
                }
            } catch (javax.naming.SizeLimitExceededException e) {
                // more than the limit — the count so far is enough for a test
            }
            return n;
        } catch (NamingException e) {
            throw new DirectoryException(explain(e));
        } finally {
            close(ctx);
        }
    }

    /** Sucht einen Benutzer mit dem Dienstkonto und löst seine Gruppen auf (ohne sein Passwort). */
    public DirectoryUser lookup(DirectoryConnection c, String bindPassword, String login) throws DirectoryException {
        LdapContext ctx = open(c, c.getBindDn(), bindPassword);
        try {
            SearchResult entry = findUser(ctx, c, login);
            return resolve(ctx, c, entry, login);
        } catch (NamingException e) {
            throw new DirectoryException(explain(e));
        } finally {
            close(ctx);
        }
    }

    /**
     * Anmeldung: Benutzer per Dienstkonto finden, dann mit SEINEM Passwort binden.
     * Gibt null zurück bei falschem Passwort/unbekanntem Benutzer (nie unterscheidbar).
     */
    public DirectoryUser authenticate(DirectoryConnection c, String bindPassword, String login, String password)
            throws DirectoryException {
        if (login == null || login.isBlank() || password == null || password.isEmpty()) {
            return null; // empty password = anonymous bind in LDAP: never allowed
        }
        LdapContext svc = open(c, c.getBindDn(), bindPassword);
        try {
            SearchResult entry;
            try {
                entry = findUser(svc, c, login);
            } catch (DirectoryException notFound) {
                return null;
            }
            LdapContext userCtx;
            try {
                userCtx = open(c, entry.getNameInNamespace(), password);
            } catch (DirectoryException badCredentials) {
                return null;
            }
            close(userCtx);
            return resolve(svc, c, entry, login);
        } catch (NamingException e) {
            throw new DirectoryException(explain(e));
        } finally {
            close(svc);
        }
    }

    /**
     * Übersetzt Prinzipale (sid:, posix-uid:, posix-gid:, ldap-dn:) in lesbare Namen,
     * höchstens 50 auf einmal. Unbekannte fehlen im Ergebnis.
     */
    public java.util.Map<String, String> resolveNames(DirectoryConnection c, String bindPassword,
                                                      java.util.Collection<String> principals) throws DirectoryException {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        LdapContext ctx = open(c, c.getBindDn(), bindPassword);
        try {
            SearchControls sc = controls(new String[]{"cn", "displayName", "sAMAccountName", "objectClass"});
            sc.setCountLimit(1);
            int n = 0;
            for (String p : principals) {
                if (n++ >= 50) break;
                String filter = null;
                if (p.startsWith("sid:")) {
                    byte[] bin = sidToBytes(p.substring(4));
                    if (bin != null) filter = "(objectSid=" + escapeBinary(bin) + ")";
                } else if (p.startsWith("posix-uid:")) {
                    filter = "(uidNumber=" + escapeFilter(p.substring(10)) + ")";
                } else if (p.startsWith("posix-gid:")) {
                    filter = "(&(gidNumber=" + escapeFilter(p.substring(10)) + ")(|(objectClass=posixGroup)(objectClass=group)))";
                }
                if (filter == null) continue;
                try {
                    NamingEnumeration<SearchResult> it = ctx.search(c.getBaseDn(), filter, sc);
                    if (hasMoreLocal(it)) {
                        Attributes a = it.next().getAttributes();
                        String name = firstNonBlank(text(a.get("displayName")), text(a.get("cn")), text(a.get("sAMAccountName")));
                        Attribute oc = a.get("objectClass");
                        boolean group = false;
                        if (oc != null) {
                            for (int i = 0; i < oc.size(); i++) {
                                String v = String.valueOf(oc.get(i)).toLowerCase(Locale.ROOT);
                                if (v.equals("group") || v.equals("posixgroup") || v.equals("groupofnames")) group = true;
                            }
                        }
                        if (name != null) out.put(p, name + (group ? " (Gruppe)" : ""));
                    }
                } catch (javax.naming.SizeLimitExceededException ignored) {
                    // first hit is enough
                }
            }
            return out;
        } catch (NamingException e) {
            throw new DirectoryException(explain(e));
        } finally {
            close(ctx);
        }
    }

    /** "S-1-5-21-…" → binary SID (inverse of sidToString), null if malformed. */
    static byte[] sidToBytes(String sid) {
        String[] parts = sid.split("-");
        if (parts.length < 3 || !"S".equals(parts[0])) return null;
        try {
            int subCount = parts.length - 3;
            byte[] b = new byte[8 + 4 * subCount];
            b[0] = (byte) Integer.parseInt(parts[1]);
            b[1] = (byte) subCount;
            long authority = Long.parseLong(parts[2]);
            for (int i = 0; i < 6; i++) b[7 - i] = (byte) (authority >>> (8 * i));
            for (int i = 0; i < subCount; i++) {
                long sub = Long.parseLong(parts[3 + i]);
                int off = 8 + 4 * i;
                b[off] = (byte) sub;
                b[off + 1] = (byte) (sub >>> 8);
                b[off + 2] = (byte) (sub >>> 16);
                b[off + 3] = (byte) (sub >>> 24);
            }
            return b;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String escapeBinary(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte x : bytes) sb.append('\\').append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    // ---------------------------------------------------------------- internals

    static void validateUrl(DirectoryConnection c) throws DirectoryException {
        URI u;
        try {
            u = URI.create(c.getUrl().trim());
        } catch (IllegalArgumentException e) {
            throw new DirectoryException("Die Adresse ist ungültig.");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (u.getHost() == null || u.getUserInfo() != null || !(scheme.equals("ldaps") || scheme.equals("ldap"))) {
            throw new DirectoryException("Die Adresse muss mit ldaps:// oder ldap:// beginnen und einen Server nennen.");
        }
        if (scheme.equals("ldap") && !c.isStartTls() && !c.isAllowPlainLdap()) {
            throw new DirectoryException("Unverschlüsselte Verbindung: bitte ldaps:// verwenden oder StartTLS einschalten.");
        }
    }

    private LdapContext open(DirectoryConnection c, String bindDn, String password) throws DirectoryException {
        validateUrl(c);
        if (password == null || password.isEmpty()) {
            throw new DirectoryException("Für die Anmeldung am Verzeichnis fehlt ein Passwort.");
        }
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, c.getUrl().trim());
        env.put(Context.REFERRAL, "ignore");
        env.put("com.sun.jndi.ldap.connect.timeout", "5000");
        env.put("com.sun.jndi.ldap.read.timeout", "15000");
        env.put("java.naming.ldap.attributes.binary", "objectSid tokenGroups");
        try {
            LdapContext ctx;
            if (c.isStartTls() && c.getUrl().trim().toLowerCase(Locale.ROOT).startsWith("ldap://")) {
                env.put(Context.SECURITY_AUTHENTICATION, "none");
                ctx = new InitialLdapContext(env, null);
                StartTlsResponse tls = (StartTlsResponse) ctx.extendedOperation(new StartTlsRequest());
                tls.negotiate();
                ctx.addToEnvironment(Context.SECURITY_AUTHENTICATION, "simple");
                ctx.addToEnvironment(Context.SECURITY_PRINCIPAL, bindDn);
                ctx.addToEnvironment(Context.SECURITY_CREDENTIALS, password);
                ctx.getAttributes(""); // forces the bind over the TLS-protected connection
            } else {
                env.put(Context.SECURITY_AUTHENTICATION, "simple");
                env.put(Context.SECURITY_PRINCIPAL, bindDn);
                env.put(Context.SECURITY_CREDENTIALS, password);
                ctx = new InitialLdapContext(env, null);
            }
            return ctx;
        } catch (javax.naming.AuthenticationException e) {
            throw new DirectoryException("Anmeldung am Verzeichnis abgelehnt (Benutzer oder Passwort falsch).");
        } catch (NamingException | java.io.IOException e) {
            throw new DirectoryException(explain(e));
        }
    }

    private SearchResult findUser(LdapContext ctx, DirectoryConnection c, String login)
            throws NamingException, DirectoryException {
        String filter = c.getUserFilter().replace("{login}", escapeFilter(login.trim()));
        SearchControls sc = controls(new String[]{"distinguishedName", "cn", "displayName", "mail",
                "userPrincipalName", "sAMAccountName", "uid", "objectSid", "uidNumber", "gidNumber",
                "userAccountControl", "pwdAccountLockedTime", "nsAccountLock",
                "sambaSID", "sambaPrimaryGroupSID"});
        sc.setCountLimit(2);
        NamingEnumeration<SearchResult> it = ctx.search(c.getBaseDn(), filter, sc);
        List<SearchResult> found = new ArrayList<>();
        try {
            while (hasMoreLocal(it)) {
                found.add(it.next());
            }
        } catch (javax.naming.SizeLimitExceededException e) {
            throw new DirectoryException("Der Benutzer ist nicht eindeutig (mehrere Treffer).");
        }
        if (found.isEmpty()) {
            throw new DirectoryException("Benutzer nicht gefunden.");
        }
        if (found.size() > 1) {
            throw new DirectoryException("Der Benutzer ist nicht eindeutig (mehrere Treffer).");
        }
        return found.get(0);
    }

    private DirectoryUser resolve(LdapContext ctx, DirectoryConnection c, SearchResult entry, String login)
            throws NamingException {
        Attributes a = entry.getAttributes();
        String dn = entry.getNameInNamespace();
        Set<String> principals = new LinkedHashSet<>();
        principals.add("ldap-dn:" + dn.toLowerCase(Locale.ROOT));
        if (DirectoryConnection.KIND_AD.equals(c.getKind())) {
            byte[] sid = binary(a.get("objectSid"));
            if (sid != null) {
                principals.add("sid:" + sidToString(sid));
            }
            // tokenGroups = all transitive security groups, computed by AD itself
            SearchControls base = new SearchControls();
            base.setSearchScope(SearchControls.OBJECT_SCOPE);
            base.setReturningAttributes(new String[]{"tokenGroups"});
            NamingEnumeration<SearchResult> it = ctx.search(dn, "(objectClass=*)", base);
            if (hasMoreLocal(it)) {
                Attribute tg = it.next().getAttributes().get("tokenGroups");
                if (tg != null) {
                    for (int i = 0; i < tg.size() && i < MAX_GROUPS; i++) {
                        if (tg.get(i) instanceof byte[] bytes) {
                            principals.add("sid:" + sidToString(bytes));
                        }
                    }
                }
            }
        } else {
            String uidNumber = text(a.get("uidNumber"));
            String gidNumber = text(a.get("gidNumber"));
            String uid = text(a.get("uid"));
            if (uidNumber != null) {
                principals.add("posix-uid:" + uidNumber.trim());
                principals.add("sid:S-1-22-1-" + uidNumber.trim()); // Samba "Unix User\…"
            }
            if (gidNumber != null) {
                principals.add("posix-gid:" + gidNumber.trim());
                principals.add("sid:S-1-22-2-" + gidNumber.trim()); // Samba "Unix Group\…"
            }
            // Samba with an LDAP backend (no AD): shares report these SIDs in their ACLs
            String sambaSid = text(a.get("sambaSID"));
            String sambaGroupSid = text(a.get("sambaPrimaryGroupSID"));
            if (sambaSid != null && sambaSid.startsWith("S-")) principals.add("sid:" + sambaSid.trim());
            if (sambaGroupSid != null && sambaGroupSid.startsWith("S-")) principals.add("sid:" + sambaGroupSid.trim());
            resolveLdapGroups(ctx, c, dn, uid, principals);
        }
        String email = firstNonBlank(text(a.get("mail")), text(a.get("userPrincipalName")));
        if (email == null && !DirectoryConnection.KIND_AD.equals(c.getKind())) {
            // Linux/LDAP accounts often have no mail: derive a stable internal address
            // uid@<domain from base DN>, e.g. uid=mmuster, dc=firma,dc=local → mmuster@firma.local
            String uidAttr = text(a.get("uid"));
            String domain = domainFromBaseDn(c.getBaseDn());
            if (uidAttr != null && !uidAttr.isBlank() && domain != null) {
                email = uidAttr.trim() + "@" + domain;
            }
        }
        String name = firstNonBlank(text(a.get("displayName")), text(a.get("cn")), login);
        // AD: userAccountControl bit 0x2 = ACCOUNTDISABLE; OpenLDAP ppolicy / 389-DS: locked markers
        boolean disabled = false;
        String uac = text(a.get("userAccountControl"));
        if (uac != null) {
            try {
                disabled = (Long.parseLong(uac.trim()) & 0x2L) != 0;
            } catch (NumberFormatException ignored) {
                // not a number: treat as enabled, AD itself still refuses the bind
            }
        }
        if (a.get("pwdAccountLockedTime") != null || "true".equalsIgnoreCase(text(a.get("nsAccountLock")))) {
            disabled = true;
        }
        return new DirectoryUser(dn, login, name, email == null ? null : email.trim().toLowerCase(Locale.ROOT),
                principals, disabled);
    }

    /** groupOfNames/groupOfUniqueNames/posixGroup, verschachtelt bis NESTED_DEPTH. */
    private void resolveLdapGroups(LdapContext ctx, DirectoryConnection c, String userDn, String uid,
                                   Set<String> principals) throws NamingException {
        SearchControls sc = controls(new String[]{"gidNumber", "sambaSID"});
        sc.setCountLimit(MAX_GROUPS);
        Deque<String[]> queue = new ArrayDeque<>();
        String first = "(|(member=" + escapeFilter(userDn) + ")(uniqueMember=" + escapeFilter(userDn) + ")"
                + (uid == null ? "" : "(memberUid=" + escapeFilter(uid) + ")") + ")";
        queue.add(new String[]{first, "0"});
        Set<String> seen = new LinkedHashSet<>();
        while (!queue.isEmpty() && seen.size() < MAX_GROUPS) {
            String[] job = queue.poll();
            int depth = Integer.parseInt(job[1]);
            NamingEnumeration<SearchResult> it = ctx.search(c.getBaseDn(), job[0], sc);
            try {
                while (hasMoreLocal(it)) {
                    SearchResult g = it.next();
                    String gdn = g.getNameInNamespace();
                    if (!seen.add(gdn.toLowerCase(Locale.ROOT))) continue;
                    principals.add("ldap-dn:" + gdn.toLowerCase(Locale.ROOT));
                    String gid = text(g.getAttributes().get("gidNumber"));
                    if (gid != null) {
                        principals.add("posix-gid:" + gid.trim());
                        principals.add("sid:S-1-22-2-" + gid.trim());
                    }
                    String gsid = text(g.getAttributes().get("sambaSID"));
                    if (gsid != null && gsid.startsWith("S-")) principals.add("sid:" + gsid.trim());
                    if (depth + 1 < NESTED_DEPTH) {
                        queue.add(new String[]{"(|(member=" + escapeFilter(gdn) + ")(uniqueMember="
                                + escapeFilter(gdn) + "))", Integer.toString(depth + 1)});
                    }
                }
            } catch (javax.naming.SizeLimitExceededException e) {
                log.warn("ldap group resolution hit the size limit");
            }
        }
    }

    /** dc=firma,dc=local → firma.local (null without dc components). */
    static String domainFromBaseDn(String baseDn) {
        List<String> dcs = new ArrayList<>();
        for (String part : baseDn.split(",")) {
            String p = part.trim();
            if (p.toLowerCase(Locale.ROOT).startsWith("dc=") && p.length() > 3) dcs.add(p.substring(3).trim().toLowerCase(Locale.ROOT));
        }
        return dcs.isEmpty() ? null : String.join(".", dcs);
    }

    private static SearchControls controls(String[] attrs) {
        SearchControls sc = new SearchControls();
        sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
        sc.setReturningAttributes(attrs);
        sc.setTimeLimit(15000);
        return sc;
    }

    /** RFC 4515 escaping for values inside a search filter. */
    static String escapeFilter(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '\\' -> sb.append("\\5c");
                case '*' -> sb.append("\\2a");
                case '(' -> sb.append("\\28");
                case ')' -> sb.append("\\29");
                case '\0' -> sb.append("\\00");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** Binary SID → "S-1-5-21-…". */
    static String sidToString(byte[] sid) {
        if (sid.length < 8) return "invalid";
        int subCount = sid[1] & 0xff;
        if (sid[0] != 1 || subCount > 15 || sid.length != 8 + subCount * 4) return "invalid";
        long authority = 0;
        for (int i = 2; i <= 7; i++) {
            authority = (authority << 8) | (sid[i] & 0xff);
        }
        StringBuilder sb = new StringBuilder("S-").append(sid[0] & 0xff).append('-').append(authority);
        for (int i = 0; i < subCount && 8 + i * 4 + 3 < sid.length; i++) {
            int off = 8 + i * 4;
            long sub = (sid[off] & 0xffL) | ((sid[off + 1] & 0xffL) << 8)
                    | ((sid[off + 2] & 0xffL) << 16) | ((sid[off + 3] & 0xffL) << 24);
            sb.append('-').append(sub);
        }
        return sb.toString();
    }

    private static byte[] binary(Attribute a) throws NamingException {
        return a == null ? null : (a.get() instanceof byte[] b ? b : null);
    }

    private static String text(Attribute a) throws NamingException {
        return a == null || a.get() == null ? null : a.get().toString();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    /** Verständliche Meldung ohne interne Details (keine Hosts/Stacktraces in der Antwort). */
    static String explain(Exception e) {
        String type = e.getClass().getSimpleName();
        if (e instanceof javax.naming.CommunicationException || type.contains("Communication")) {
            return "Der Verzeichnisserver ist nicht erreichbar (Adresse, Port, Firewall oder Zertifikat prüfen).";
        }
        if (e instanceof javax.naming.NameNotFoundException) {
            return "Der Such-Startpunkt (Base DN) existiert nicht.";
        }
        if (e instanceof javax.naming.directory.InvalidSearchFilterException) {
            return "Der Benutzerfilter ist ungültig.";
        }
        if (e instanceof java.io.IOException) {
            return "Die verschlüsselte Verbindung (StartTLS) konnte nicht aufgebaut werden.";
        }
        return "Das Verzeichnis hat die Anfrage abgelehnt (" + type + ").";
    }

    private static void close(LdapContext ctx) {
        if (ctx != null) {
            try {
                ctx.close();
            } catch (NamingException ignored) {
                // nothing to do
            }
        }
    }
}
