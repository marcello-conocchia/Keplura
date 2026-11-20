package com.keplura.server.identity;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Hash/verify utility for user passwords (M2a). Argon2id with the
 * Spring Security 5.8+ recommended defaults; the encoded form is self-describing
 * ({@code $argon2id$v=19$m=...,t=...,p=...$salt$hash}), so parameters can be
 * evolved later without ambiguity (see docs/adr/0002).
 *
 * <p>Deliberately narrow: this is hashing only. No Spring Security filter
 * chain, sessions or login endpoints exist here — that is M2b.
 * BouncyCastle must be on the classpath (see pom) — the encoder requires it for Argon2.
 */
@Component
public class PasswordHasher {

    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    /** Returns an Argon2id hash for {@code rawPassword}; never store the raw value. */
    public String hash(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("password must not be empty");
        }
        return encoder.encode(rawPassword);
    }

    /** True iff {@code rawPassword} matches the stored Argon2 hash. */
    public boolean verify(String rawPassword, String passwordHash) {
        if (rawPassword == null || passwordHash == null) {
            return false;
        }
        return encoder.matches(rawPassword, passwordHash);
    }
}
