package com.keplura.server.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M2a: persistence behaviour of {@link User} / {@link UserRepository} against
 * embedded H2 via slice test (no web server, no external database — ADR-0002).
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:identity-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class UserRepositoryTest {

    private static final PasswordHasher HASHER = new PasswordHasher();

    @Autowired
    private UserRepository repository;

    @Test
    void persistsAndReadsBackUserWithHashAndCreatedAt() {
        User user = User.create("alice@example.com", HASHER.hash("wonderland"), "Alice");

        User saved = repository.saveAndFlush(user);

        assertThat(saved.getId()).isNotNull();
        Optional<User> found = repository.findByEmail("alice@example.com");
        assertThat(found).isPresent();
        assertThat(found.get().getDisplayName()).isEqualTo("Alice");
        assertThat(found.get().getCreatedAt()).isNotNull();
        // stored value is an Argon2 hash, never the raw password
        assertThat(found.get().getPasswordHash()).startsWith("$argon2id$");
        assertThat(found.get().getPasswordHash()).doesNotContain("wonderland");
        assertThat(HASHER.verify("wonderland", found.get().getPasswordHash())).isTrue();
    }

    @Test
    void emailIsUniqueAcrossUsers() {
        repository.saveAndFlush(User.create("bob@example.com", HASHER.hash("pw-one"), "Bob"));

        User duplicate = User.create("bob@example.com", HASHER.hash("pw-two"), "Bobby");
        assertThatThrownBy(() -> repository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void emailLookupIsCaseAndWhitespaceInsensitive() {
        // create() normalises the stored key, so mixed case / padding map to the
        // same lookup value; the unique constraint test above proves the DB
        // rejects the duplicate.
        User normalized = User.create("  Carol@Example.COM ", HASHER.hash("pw"), "Carol");
        assertThat(normalized.getEmail()).isEqualTo("carol@example.com");

        repository.saveAndFlush(User.create("carol@example.com", HASHER.hash("pw2"), "Carol II"));
        // repository methods take the normalised form — M2b callers use User.normalizeEmail
        assertThat(repository.findByEmail(User.normalizeEmail("  CAROL@example.COM "))).isPresent();
        assertThat(repository.existsByEmail("carol@example.com")).isTrue();
    }

    @Test
    void emailNormalizationIsLocaleInvariant() {
        // Turkish-I guard: with the JVM default locale tr-TR, a locale-sensitive
        // toLowerCase() folds "I" to "ı" and would corrupt the unique login key.
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(User.normalizeEmail("  ILKAY@ISTANBUL.COM "))
                    .isEqualTo("ilkay@istanbul.com");
            assertThat(User.create("  ILKAY@ISTANBUL.COM ", HASHER.hash("pw"), "Ilkay").getEmail())
                    .isEqualTo("ilkay@istanbul.com");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void createRejectsBlankFields() {
        String hash = HASHER.hash("pw");
        assertThatThrownBy(() -> User.create(null, hash, "X"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> User.create(" ", hash, "X"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> User.create("d@e.io", null, "X"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> User.create("d@e.io", hash, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
