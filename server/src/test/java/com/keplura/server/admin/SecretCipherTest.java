package com.keplura.server.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADMIN-KI Scope B (docs/adr/0008 §4): AES-256-GCM secret envelopes with an
 * HKDF-derived key. Round-trip honesty, per-call IV freshness, tamper
 * rejection (GCM auth, never garbage out), a wrong master key never
 * decrypting, and the degraded no-key mode that answers 503 instead of
 * boot-failing.
 */
class SecretCipherTest {

    private static SecretCipher withKey(String key) {
        return new SecretCipher(key);
    }

    @Test
    void roundTripReturnsTheExactPlaintext() {
        SecretCipher cipher = withKey("master-a");
        String sealed = cipher.encrypt("sk-TopSecret-123");
        assertThat(sealed).isNotEqualTo("sk-TopSecret-123").doesNotContain("TopSecret");
        assertThat(cipher.decrypt(sealed)).isEqualTo("sk-TopSecret-123");
    }

    @Test
    void eachCallUsesAFreshIvSoEqualPlaintextsDifferInCiphertext() {
        SecretCipher cipher = withKey("master-a");
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void flippedBitIsRejectedNotMisread() {
        SecretCipher cipher = withKey("master-a");
        String sealed = cipher.encrypt("value");
        char[] chars = sealed.toCharArray();
        // flip one payload char deterministically (Base64 alphabet safe pick)
        chars[chars.length - 3] = chars[chars.length - 3] == 'A' ? 'B' : 'A';
        String tampered = new String(chars);
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void wrongMasterKeyNeverDecrypts() {
        String sealed = withKey("master-a").encrypt("value");
        assertThatThrownBy(() -> withKey("master-b").decrypt(sealed))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void noMasterKeyDegradesHonestNotFatal() {
        SecretCipher cipher = withKey("");
        assertThat(cipher.available()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("x"))
                .isInstanceOfSatisfying(AdminException.class,
                        e -> assertThat(e.code()).isEqualTo(AdminException.MASTER_KEY_MISSING));
    }

    @Test
    void garbageEnvelopeIsRejected() {
        SecretCipher cipher = withKey("master-a");
        assertThatThrownBy(() -> cipher.decrypt("not base64 at all !!!"))
                .isInstanceOf(RuntimeException.class);
    }
}
