package com.keplura.server.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** ADR-0012 §2: one canonical principal of a user (sid:…, posix-uid:…, ldap-dn:…). */
@Entity
@Table(name = "user_principal")
public class UserPrincipal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 300)
    private String principal;

    @Column(name = "refreshed_at", nullable = false)
    private Instant refreshedAt;

    protected UserPrincipal() {
    }

    public UserPrincipal(Long userId, String principal) {
        this.userId = userId;
        this.principal = principal;
        this.refreshedAt = Instant.now();
    }

    public Long getUserId() {
        return userId;
    }

    public String getPrincipal() {
        return principal;
    }

    public Instant getRefreshedAt() {
        return refreshedAt;
    }
}
