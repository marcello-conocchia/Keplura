package com.keplura.server.directory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/** ADR-0012 §2: Active-Directory- oder LDAP-Anbindung einer Organisation. */
@Entity
@Table(name = "directory_connection")
public class DirectoryConnection {

    public static final String KIND_AD = "AD";
    public static final String KIND_LDAP = "LDAP";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "org_id", nullable = false)
    private Long orgId;
    @Column(nullable = false, length = 20)
    private String kind;
    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;
    @Column(nullable = false, length = 500)
    private String url;
    @Column(name = "base_dn", nullable = false, length = 500)
    private String baseDn;
    @Column(name = "bind_dn", nullable = false, length = 500)
    private String bindDn;
    @Column(name = "bind_secret_cipher", length = 2000)
    private String bindSecretCipher;
    @Column(name = "user_filter", nullable = false, length = 500)
    private String userFilter;
    @Column(name = "start_tls", nullable = false)
    private boolean startTls;
    @Column(name = "allow_plain_ldap", nullable = false)
    private boolean allowPlainLdap;
    @Column(name = "login_enabled", nullable = false)
    private boolean loginEnabled;
    @Column(name = "auto_provision", nullable = false)
    private boolean autoProvision;
    @Column(nullable = false)
    private boolean enabled;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DirectoryConnection() {
    }

    public DirectoryConnection(Long orgId) {
        this.orgId = orgId;
    }

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Default user filters; {login} is replaced by the RFC-4515-escaped login. */
    public static String defaultUserFilter(String kind) {
        return KIND_AD.equals(kind)
                ? "(&(objectCategory=person)(objectClass=user)(|(sAMAccountName={login})(userPrincipalName={login})(mail={login})))"
                : "(&(objectClass=inetOrgPerson)(|(uid={login})(mail={login})))";
    }

    public Long getId() { return id; }
    public Long getOrgId() { return orgId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getBaseDn() { return baseDn; }
    public void setBaseDn(String baseDn) { this.baseDn = baseDn; }
    public String getBindDn() { return bindDn; }
    public void setBindDn(String bindDn) { this.bindDn = bindDn; }
    public String getBindSecretCipher() { return bindSecretCipher; }
    public void setBindSecretCipher(String bindSecretCipher) { this.bindSecretCipher = bindSecretCipher; }
    public boolean hasSecret() { return bindSecretCipher != null && !bindSecretCipher.isBlank(); }
    public String getUserFilter() { return userFilter; }
    public void setUserFilter(String userFilter) { this.userFilter = userFilter; }
    public boolean isStartTls() { return startTls; }
    public void setStartTls(boolean startTls) { this.startTls = startTls; }
    public boolean isAllowPlainLdap() { return allowPlainLdap; }
    public void setAllowPlainLdap(boolean allowPlainLdap) { this.allowPlainLdap = allowPlainLdap; }
    public boolean isLoginEnabled() { return loginEnabled; }
    public void setLoginEnabled(boolean loginEnabled) { this.loginEnabled = loginEnabled; }
    public boolean isAutoProvision() { return autoProvision; }
    public void setAutoProvision(boolean autoProvision) { this.autoProvision = autoProvision; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getUpdatedAt() { return updatedAt; }
}
