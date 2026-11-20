package com.keplura.server.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M2a: Argon2 hash/verify roundtrip (docs/adr/0002). Pure unit test — no Spring
 * context, no database.
 */
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashThenVerifyAcceptsTheSamePassword() {
        String hash = hasher.hash("correct horse battery staple");

        assertThat(hash).startsWith("$argon2id$");
        assertThat(hash).isNotEqualTo("correct horse battery staple");
        assertThat(hasher.verify("correct horse battery staple", hash)).isTrue();
    }

    @Test
    void verifyRejectsWrongPassword() {
        String hash = hasher.hash("s3cure-passphrase");

        assertThat(hasher.verify("s3 cure-passphrase", hash)).isFalse();
        assertThat(hasher.verify("S3CURE-PASSPHRASE", hash)).isFalse();
        assertThat(hasher.verify("", hash)).isFalse();
        assertThat(hasher.verify(null, hash)).isFalse();
        assertThat(hasher.verify("anything", null)).isFalse();
    }

    @Test
    void hashingTwiceProducesDifferentSaltedHashes() {
        String first = hasher.hash("same-password");
        String second = hasher.hash("same-password");

        assertThat(first).isNotEqualTo(second);
        // both independently verify the original password
        assertThat(hasher.verify("same-password", first)).isTrue();
        assertThat(hasher.verify("same-password", second)).isTrue();
    }

    @Test
    void hashRejectsEmptyOrNullPassword() {
        assertThatThrownBy(() -> hasher.hash(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hasher.hash(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
