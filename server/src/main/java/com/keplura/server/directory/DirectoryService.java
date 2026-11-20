package com.keplura.server.directory;

import com.keplura.server.access.AccessFilter;
import com.keplura.server.access.UserPrincipal;
import com.keplura.server.access.UserPrincipalRepository;
import com.keplura.server.admin.SecretCipher;
import com.keplura.server.auth.AuthPrincipal;
import com.keplura.server.identity.PasswordHasher;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import com.keplura.server.org.Membership;
import com.keplura.server.org.MembershipRepository;
import com.keplura.server.org.MembershipRole;
import com.keplura.server.org.OrganizationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * ADR-0012 §2 — Verzeichnis-Anbindungen verwalten und Anmeldung über AD/LDAP.
 *
 * <p>Anmeldung: lokale Konten haben Vorrang (AuthService). Schlägt die lokale
 * Prüfung fehl, werden aktive Verbindungen mit erlaubter Anmeldung der Reihe
 * nach gefragt. Bei Erfolg: lokales Konto per E-Mail finden oder — nur wenn
 * die Verbindung es erlaubt — anlegen (ohne nutzbares lokales Passwort),
 * Mitgliedschaft in der Organisation der Verbindung sicherstellen und die
 * aufgelösten Prinzipale (SIDs, POSIX-IDs, Gruppen-DNs) ersetzen.
 */
@Service
public class DirectoryService {

    private static final Logger log = LoggerFactory.getLogger(DirectoryService.class);

    public static class DirectoryInputException extends RuntimeException {
        public DirectoryInputException(String message) {
            super(message);
        }
    }

    public record ConnectionView(Long id, Long orgId, String kind, String displayName, String url, String baseDn,
                                 String bindDn, boolean secretSet, String userFilter, boolean startTls,
                                 boolean allowPlainLdap, boolean loginEnabled, boolean autoProvision,
                                 boolean enabled) {
    }

    public record ConnectionInput(Long orgId, String kind, String displayName, String url, String baseDn,
                                  String bindDn, String bindPassword, String userFilter, Boolean startTls,
                                  Boolean allowPlainLdap, Boolean loginEnabled, Boolean autoProvision,
                                  Boolean enabled) {
    }

    public record TestResult(boolean ok, String message, int usersFound) {
    }

    public record LookupResult(boolean found, String message, String displayName, String email,
                               List<String> principals) {
    }

    private final DirectoryConnectionRepository repository;
    private final LdapDirectoryClient client;
    private final SecretCipher secretCipher;
    private final UserRepository userRepository;
    private final UserPrincipalRepository principalRepository;
    private final MembershipRepository membershipRepository;
    private final OrganizationRepository organizationRepository;
    private final PasswordHasher passwordHasher;
    private org.springframework.transaction.support.TransactionTemplate tx;

    @org.springframework.beans.factory.annotation.Autowired
    void setTransactionManager(org.springframework.transaction.PlatformTransactionManager txManager) {
        this.tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
    }

    public DirectoryService(DirectoryConnectionRepository repository, LdapDirectoryClient client,
                            SecretCipher secretCipher, UserRepository userRepository,
                            UserPrincipalRepository principalRepository, MembershipRepository membershipRepository,
                            OrganizationRepository organizationRepository, PasswordHasher passwordHasher) {
        this.repository = repository;
        this.client = client;
        this.secretCipher = secretCipher;
        this.userRepository = userRepository;
        this.principalRepository = principalRepository;
        this.membershipRepository = membershipRepository;
        this.organizationRepository = organizationRepository;
        this.passwordHasher = passwordHasher;
    }

    // ------------------------------------------------------------ admin CRUD

    @Transactional(readOnly = true)
    public List<ConnectionView> list() {
        return repository.findAllByOrderByIdAsc().stream().map(DirectoryService::view).toList();
    }

    @Transactional
    public ConnectionView create(ConnectionInput in) {
        if (in == null || in.orgId() == null || organizationRepository.findById(in.orgId()).isEmpty()) {
            throw new DirectoryInputException("Bitte eine bestehende Organisation wählen.");
        }
        DirectoryConnection c = new DirectoryConnection(in.orgId());
        apply(c, in, true);
        return view(repository.saveAndFlush(c));
    }

    @Transactional
    public ConnectionView update(Long id, ConnectionInput in) {
        DirectoryConnection c = repository.findById(id).orElseThrow(() -> new DirectoryInputException("not-found"));
        apply(c, in, false);
        return view(repository.saveAndFlush(c));
    }

    @Transactional
    public void delete(Long id) {
        repository.findById(id).ifPresent(repository::delete);
    }

    public TestResult test(Long id) {
        DirectoryConnection c = repository.findById(id).orElseThrow(() -> new DirectoryInputException("not-found"));
        try {
            int n = client.testConnection(c, secret(c));
            return new TestResult(true, n == 0
                    ? "Verbunden, aber der Benutzerfilter findet niemanden. Base DN und Filter prüfen."
                    : "Verbunden. " + (n >= 1000 ? "Mehr als 1.000" : n) + " Benutzer gefunden.", n);
        } catch (LdapDirectoryClient.DirectoryException e) {
            return new TestResult(false, e.getMessage(), 0);
        }
    }

    public LookupResult lookup(Long id, String login) {
        DirectoryConnection c = repository.findById(id).orElseThrow(() -> new DirectoryInputException("not-found"));
        if (login == null || login.isBlank() || login.length() > 300) {
            throw new DirectoryInputException("Bitte einen Benutzernamen oder eine E-Mail-Adresse eingeben.");
        }
        try {
            LdapDirectoryClient.DirectoryUser u = client.lookup(c, secret(c), login);
            return new LookupResult(true, "Gefunden.", u.displayName(), u.email(), List.copyOf(u.principals()));
        } catch (LdapDirectoryClient.DirectoryException e) {
            return new LookupResult(false, e.getMessage(), null, null, List.of());
        }
    }

    /** Lesbare Namen für Prinzipale über die aktiven Verbindungen einer Organisation (best effort). */
    public java.util.Map<String, String> resolveNames(Long orgId, java.util.Collection<String> principals) {
        java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
        for (DirectoryConnection c : repository.findAllByOrderByIdAsc()) {
            if (!c.isEnabled() || !c.getOrgId().equals(orgId)) continue;
            List<String> open = principals.stream().filter(p -> !names.containsKey(p)).toList();
            if (open.isEmpty()) break;
            try {
                names.putAll(client.resolveNames(c, secret(c), open));
            } catch (LdapDirectoryClient.DirectoryException | RuntimeException e) {
                log.info("directory name resolution skipped for connection {} ({})", c.getId(), e.getClass().getSimpleName());
            }
        }
        return names;
    }

    // ------------------------------------------------------------ login

    /**
     * Versucht die Anmeldung über alle aktiven Verbindungen mit erlaubter Anmeldung.
     * Leer = keine Verbindung hat den Benutzer bestätigt (nie unterscheidbar nach außen).
     */
    @Transactional
    public Optional<AuthPrincipal> authenticate(String login, String password) {
        for (DirectoryConnection c : repository.findByEnabledTrueAndLoginEnabledTrueOrderByIdAsc()) {
            LdapDirectoryClient.DirectoryUser du;
            try {
                du = client.authenticate(c, secret(c), login, password);
            } catch (LdapDirectoryClient.DirectoryException | RuntimeException e) {
                log.warn("directory login: connection {} unavailable ({})", c.getId(), e.getClass().getSimpleName());
                continue;
            }
            if (du == null || du.disabled()) {
                continue;
            }
            if (du.email() == null || !du.email().contains("@")) {
                log.warn("directory login: user in connection {} has no e-mail attribute", c.getId());
                continue;
            }
            Optional<User> existing = userRepository.findByEmail(User.normalizeEmail(du.email()));
            User user;
            if (existing.isPresent()) {
                user = existing.get();
                if (user.isAdmin()) {
                    // Administratoren melden sich nur lokal an: sonst könnte ein gleichnamiges
                    // Verzeichniskonto (gleiche E-Mail) die Keplura-Administration übernehmen.
                    log.warn("directory login refused for an administrator account (connection {})", c.getId());
                    continue;
                }
            } else if (c.isAutoProvision()) {
                // no usable local password: this account signs in through the directory only
                user = userRepository.saveAndFlush(User.create(User.normalizeEmail(du.email()),
                        passwordHasher.hash("directory-only-" + UUID.randomUUID()), du.displayName()));
            } else {
                log.info("directory login: no local account and auto-provisioning off (connection {})", c.getId());
                continue;
            }
            if (c.isAutoProvision() && membershipRepository.findByOrgIdAndUserId(c.getOrgId(), user.getId()).isEmpty()) {
                membershipRepository.saveAndFlush(Membership.grant(c.getOrgId(), user.getId(), MembershipRole.MEMBER));
            }
            replacePrincipals(user.getId(), du.principals());
            return Optional.of(new AuthPrincipal(user.getId(), user.getDisplayName()));
        }
        return Optional.empty();
    }

    /**
     * ADR-0012 §3: Gruppen aller Verzeichnis-Benutzer regelmäßig auffrischen (≤ 15 min),
     * damit entzogene Rechte nicht bis zur nächsten Anmeldung weiter gelten.
     * Nicht mehr gefunden oder deaktiviert → alle Prinzipale entfernen (fail-closed;
     * hand-hochgeladene Dokumente bleiben über die Mitgliedschaft sichtbar).
     * Ist KEIN Verzeichnis erreichbar, bleibt der Stand unverändert (Verfügbarkeit),
     * es wird aber gewarnt.
     */
    public int refreshAllPrincipals() {
        List<DirectoryConnection> conns = repository.findAllByOrderByIdAsc().stream()
                .filter(DirectoryConnection::isEnabled).toList();
        if (conns.isEmpty()) return 0;
        java.util.Set<Long> userIds = new java.util.LinkedHashSet<>();
        principalRepository.findAll().forEach(p -> {
            if (!p.getPrincipal().startsWith("entra")) userIds.add(p.getUserId()); // directory users only
        });
        int changed = 0;
        for (Long userId : userIds) {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                tx.executeWithoutResult(t -> principalRepository.deleteDirectoryPrincipals(userId));
                continue;
            }
            boolean reachable = false;
            LdapDirectoryClient.DirectoryUser found = null;
            for (DirectoryConnection c : conns) {
                try {
                    found = client.lookup(c, secret(c), user.getEmail());
                    reachable = true;
                    break;
                } catch (LdapDirectoryClient.DirectoryException e) {
                    // "not found" also lands here; a reachable server answered, so count it as reachable
                    if (e.getMessage() != null && e.getMessage().startsWith("Benutzer nicht")) reachable = true;
                } catch (RuntimeException e) {
                    log.warn("principal refresh: connection {} failed ({})", c.getId(), e.getClass().getSimpleName());
                }
            }
            if (!reachable) {
                log.warn("principal refresh: no directory reachable, keeping principals of user {}", userId);
                continue;
            }
            // self-invocation bypasses @Transactional → explicit transaction per user
            final Set<String> next = (found == null || found.disabled()) ? Set.of() : found.principals();
            tx.executeWithoutResult(t -> replacePrincipals(userId, next));
            changed++;
        }
        return changed;
    }

    /** Ersetzt die gespeicherten Prinzipale eines Benutzers (eine Transaktion). */
    @Transactional
    public void replacePrincipals(Long userId, Set<String> principals) {
        principalRepository.deleteDirectoryPrincipals(userId); // Entra principals are managed by the OIDC login
        principalRepository.flush();
        for (String p : principals) {
            if (p != null && !p.isBlank() && p.length() <= 300 && !AccessFilter.EVERYONE.equals(p)
                    && !p.startsWith("entra")) {
                principalRepository.save(new UserPrincipal(userId, p));
            }
        }
        principalRepository.flush();
    }

    // ------------------------------------------------------------ helpers

    private String secret(DirectoryConnection c) {
        if (!c.hasSecret()) {
            return null;
        }
        if (!secretCipher.available()) {
            throw new DirectoryInputException("Auf dem Server fehlt der Master-Key für gespeicherte Passwörter.");
        }
        return secretCipher.decrypt(c.getBindSecretCipher());
    }

    private void apply(DirectoryConnection c, ConnectionInput in, boolean creating) {
        if (in == null) throw new DirectoryInputException("Angaben fehlen.");
        String kind = in.kind() == null ? c.getKind() : in.kind().trim().toUpperCase(Locale.ROOT);
        if (!DirectoryConnection.KIND_AD.equals(kind) && !DirectoryConnection.KIND_LDAP.equals(kind)) {
            throw new DirectoryInputException("Typ muss Active Directory (AD) oder LDAP sein.");
        }
        c.setKind(kind);
        c.setDisplayName(required(in.displayName(), c.getDisplayName(), "Anzeigename", 200, creating));
        c.setUrl(required(in.url(), c.getUrl(), "Adresse", 500, creating));
        c.setBaseDn(required(in.baseDn(), c.getBaseDn(), "Base DN", 500, creating));
        c.setBindDn(required(in.bindDn(), c.getBindDn(), "Dienstkonto", 500, creating));
        String filter = in.userFilter() == null || in.userFilter().isBlank()
                ? (c.getUserFilter() == null ? DirectoryConnection.defaultUserFilter(kind) : c.getUserFilter())
                : in.userFilter().trim();
        if (!filter.contains("{login}") || filter.length() > 500) {
            throw new DirectoryInputException("Der Benutzerfilter muss den Platzhalter {login} enthalten.");
        }
        c.setUserFilter(filter);
        if (in.startTls() != null) c.setStartTls(in.startTls());
        if (in.allowPlainLdap() != null) c.setAllowPlainLdap(in.allowPlainLdap());
        if (in.loginEnabled() != null) c.setLoginEnabled(in.loginEnabled());
        if (in.autoProvision() != null) c.setAutoProvision(in.autoProvision());
        c.setEnabled(in.enabled() == null ? (creating || c.isEnabled()) : in.enabled());
        if (in.bindPassword() != null && !in.bindPassword().isEmpty()) {
            if (!secretCipher.available()) {
                throw new DirectoryInputException("Auf dem Server fehlt der Master-Key (KEPLURA_SECRETS_MASTER_KEY); "
                        + "das Passwort des Dienstkontos kann nicht sicher gespeichert werden.");
            }
            c.setBindSecretCipher(secretCipher.encrypt(in.bindPassword()));
        }
        try {
            LdapDirectoryClient.validateUrl(c);
        } catch (LdapDirectoryClient.DirectoryException e) {
            throw new DirectoryInputException(e.getMessage());
        }
    }

    private static String required(String value, String current, String label, int max, boolean creating) {
        if (value == null || value.isBlank()) {
            if (!creating && current != null) return current;
            throw new DirectoryInputException(label + " fehlt.");
        }
        String v = value.trim();
        if (v.length() > max) throw new DirectoryInputException(label + " ist zu lang.");
        return v;
    }

    private static ConnectionView view(DirectoryConnection c) {
        return new ConnectionView(c.getId(), c.getOrgId(), c.getKind(), c.getDisplayName(), c.getUrl(), c.getBaseDn(),
                c.getBindDn(), c.hasSecret(), c.getUserFilter(), c.isStartTls(), c.isAllowPlainLdap(),
                c.isLoginEnabled(), c.isAutoProvision(), c.isEnabled());
    }
}
