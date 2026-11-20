package com.keplura.server.datasource;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msdtyp.SecurityDescriptor;
import com.hierynomus.msdtyp.SecurityInformation;
import com.hierynomus.msdtyp.ace.ACE;
import com.hierynomus.msdtyp.ace.AceFlags;
import com.hierynomus.msdtyp.ace.AceType;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.keplura.server.access.AccessFilter;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * ADR-0012 §1 Typ 1 — Windows-Freigabe über SMB2/3 (Windows Server, Samba),
 * DFS-Verweise werden aufgelöst. Nur lesender Zugriff mit einem Dienstkonto.
 *
 * <p>Rechte: die effektive DACL der Datei (vererbte Einträge sind dort bereits
 * enthalten). Allow-/Deny-Einträge mit Leserecht (FILE_READ_DATA, GENERIC_READ,
 * GENERIC_ALL) werden als {@code sid:}-Prinzipale übernommen; „Jeder“ (S-1-1-0)
 * und „Authentifizierte Benutzer“ (S-1-5-11) werden zu {@code everyone}.
 * Nur-vererbbare Einträge (INHERIT_ONLY) gelten nicht für die Datei selbst.
 * NICHT ausgewertet in dieser Version: Freigabe-Berechtigungen (Share-ACL) —
 * Empfehlung: Freigabe „Jeder: Lesen“, Steuerung über NTFS (Microsoft-Praxis).
 */
public class SmbSourceConnector implements SourceConnector {

    private static final long READ_BITS = AccessMask.FILE_READ_DATA.getValue()
            | AccessMask.GENERIC_READ.getValue() | AccessMask.GENERIC_ALL.getValue() | AccessMask.MAXIMUM_ALLOWED.getValue();

    private final SMBClient client;
    private final Connection connection;
    private final Session session;
    private final DiskShare share;
    private final String basePath;

    /** location: \\server\share\optional\path  or  smb://server/share/optional/path */
    public SmbSourceConnector(String location, String username, String password) throws IOException {
        String norm = location.trim().replace('\\', '/');
        if (norm.toLowerCase(Locale.ROOT).startsWith("smb://")) norm = norm.substring(6);
        while (norm.startsWith("/")) norm = norm.substring(1);
        String[] parts = norm.split("/", 3);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IOException("Bitte die Freigabe als \\\\server\\freigabe\\ordner angeben.");
        }
        String host = parts[0];
        String shareName = parts[1];
        this.basePath = parts.length == 3 ? parts[2].replace('/', '\\').replaceAll("\\\\+$", "") : "";
        String domain = "";
        String user = username == null ? "" : username.trim();
        int slash = user.indexOf('\\');
        if (slash > 0) {
            domain = user.substring(0, slash);
            user = user.substring(slash + 1);
        }
        SmbConfig config = SmbConfig.builder()
                .withDfsEnabled(true)
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(60, TimeUnit.SECONDS)
                .build();
        this.client = new SMBClient(config);
        try {
            this.connection = client.connect(host);
            this.session = connection.authenticate(new AuthenticationContext(user,
                    password == null ? new char[0] : password.toCharArray(), domain));
            this.share = (DiskShare) session.connectShare(shareName);
        } catch (IOException | RuntimeException e) {
            close();
            throw new IOException(explain(e));
        }
    }

    @Override
    public List<Entry> list(int limit) throws IOException {
        List<Entry> out = new ArrayList<>();
        Deque<String> dirs = new ArrayDeque<>();
        dirs.add("");
        try {
            while (!dirs.isEmpty() && out.size() < limit) {
                String rel = dirs.poll();
                for (FileIdBothDirectoryInformation f : share.list(join(rel))) {
                    String name = f.getFileName();
                    if (".".equals(name) || "..".equals(name)) continue;
                    String childRel = rel.isEmpty() ? name : rel + "/" + name;
                    long attrs = f.getFileAttributes();
                    boolean directory = (attrs & 0x10L) != 0;
                    boolean reparse = (attrs & 0x400L) != 0; // links/junctions are never followed
                    if (reparse) continue;
                    if (directory) {
                        dirs.add(childRel);
                    } else {
                        out.add(new Entry(childRel, f.getEndOfFile(),
                                Instant.ofEpochMilli(f.getLastWriteTime().toEpochMillis())));
                        if (out.size() >= limit) break;
                    }
                }
            }
        } catch (RuntimeException e) {
            throw new IOException(explain(e));
        }
        return out;
    }

    @Override
    public InputStream open(String relativePath) throws IOException {
        try {
            com.hierynomus.smbj.share.File file = share.openFile(join(relativePath),
                    EnumSet.of(AccessMask.GENERIC_READ), null, EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN, null);
            InputStream in = file.getInputStream();
            return new java.io.FilterInputStream(in) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        file.close();
                    }
                }
            };
        } catch (RuntimeException e) {
            throw new IOException(explain(e));
        }
    }

    @Override
    public FileAcl acl(String relativePath) throws IOException {
        SecurityDescriptor sd;
        try {
            sd = share.getSecurityInfo(join(relativePath), EnumSet.of(SecurityInformation.DACL_SECURITY_INFORMATION));
        } catch (RuntimeException e) {
            throw new IOException(explain(e));
        }
        return permissions(sd);
    }

    static FileAcl permissions(SecurityDescriptor sd) {
        if (sd == null) return FileAcl.unknown();
        if (sd.getDacl() == null) {
            // NULL DACL grants everyone full access in Windows semantics
            return new FileAcl(Set.of(AccessFilter.EVERYONE), Set.of());
        }
        Set<String> allow = new LinkedHashSet<>();
        Set<String> deny = new LinkedHashSet<>();
        for (ACE ace : sd.getDacl().getAces()) {
            if (ace.getAceHeader().getAceFlags().contains(AceFlags.INHERIT_ONLY_ACE)) continue;
            if ((ace.getAccessMask() & READ_BITS) == 0) continue;
            String principal = principal(ace.getSid().toString());
            AceType type = ace.getAceHeader().getAceType();
            if (type == AceType.ACCESS_ALLOWED_ACE_TYPE || type == AceType.ACCESS_ALLOWED_OBJECT_ACE_TYPE) {
                allow.add(principal);
            } else if (type == AceType.ACCESS_DENIED_ACE_TYPE || type == AceType.ACCESS_DENIED_OBJECT_ACE_TYPE) {
                deny.add(principal);
            }
        }
        return new FileAcl(allow, deny);
    }

    static String principal(String sid) {
        return switch (sid) {
            case "S-1-1-0", "S-1-5-11" -> AccessFilter.EVERYONE; // Jeder / Authentifizierte Benutzer
            default -> {
                // Samba without a domain reports POSIX owners as "Unix User/Group" SIDs
                if (sid.startsWith("S-1-22-1-")) yield "posix-uid:" + sid.substring(9);
                if (sid.startsWith("S-1-22-2-")) yield "posix-gid:" + sid.substring(9);
                yield "sid:" + sid;
            }
        };
    }

    private String join(String rel) {
        String r = rel.replace('/', '\\');
        if (basePath.isEmpty()) return r;
        return r.isEmpty() ? basePath : basePath + "\\" + r;
    }

    static String explain(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("STATUS_LOGON_FAILURE")) return "Anmeldung an der Freigabe abgelehnt (Benutzer oder Passwort falsch).";
        if (m.contains("STATUS_BAD_NETWORK_NAME")) return "Die Freigabe wurde auf dem Server nicht gefunden.";
        if (m.contains("STATUS_ACCESS_DENIED")) return "Das Dienstkonto hat keinen Lesezugriff auf diesen Ordner.";
        if (m.contains("STATUS_OBJECT_PATH_NOT_FOUND") || m.contains("STATUS_OBJECT_NAME_NOT_FOUND")) {
            return "Der Ordner wurde in der Freigabe nicht gefunden.";
        }
        if (e instanceof java.net.UnknownHostException || m.contains("UnknownHost")) return "Der Server-Name ist unbekannt.";
        return "Die Freigabe ist nicht erreichbar (" + e.getClass().getSimpleName() + ").";
    }

    @Override
    public void close() {
        try { if (share != null) share.close(); } catch (Exception ignored) { }
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        try { if (connection != null) connection.close(); } catch (Exception ignored) { }
        try { if (client != null) client.close(); } catch (Exception ignored) { }
    }
}
