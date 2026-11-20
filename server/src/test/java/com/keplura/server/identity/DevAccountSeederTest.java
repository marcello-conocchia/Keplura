package com.keplura.server.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2d dev account seeding: with both seed properties set (dev profile is the
 * default), startup creates exactly one loginable user with a normalized
 * email, and re-running the seeder never resets an existing password. The
 * "no properties -> no bean" half of the gate is the standard Spring
 * {@code @ConditionalOnProperty} behaviour the class documents; every other
 * test class in this suite boots without the seed properties and would fail
 * if the bean were unconditional (unique-count assertions).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:seeder-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "keplura.dev.seed.email=Seeded@Example.COM",
        "keplura.dev.seed.password=seeded-pw",
        "keplura.dev.seed.display-name=Seeded QA"
})
class DevAccountSeederTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordHasher passwordHasher;

    @Test
    void seederRunsAndCreatesNormalizedUserOnce() {
        assertThat(context.getBeanNamesForType(DevAccountSeeder.class)).hasSize(1);

        User user = userRepository.findByEmail("seeded@example.com").orElseThrow();
        assertThat(user.getDisplayName()).isEqualTo("Seeded QA");
        assertThat(passwordHasher.verify("seeded-pw", user.getPasswordHash())).isTrue();
        // Exactly one row for the seed, even though the context booted once.
        assertThat(userRepository.count()).isEqualTo(1L);
    }

    /** Re-running the seeder (simulate restart) must not touch the existing row. */
    @Test
    void seederIsIdempotentAndNeverResetsPasswords() {
        User before = userRepository.findByEmail("seeded@example.com").orElseThrow();
        String hashBefore = before.getPasswordHash();

        context.getBean(DevAccountSeeder.class).run();
        User after = userRepository.findByEmail("seeded@example.com").orElseThrow();
        assertThat(after.getPasswordHash()).isEqualTo(hashBefore);
        assertThat(userRepository.count()).isEqualTo(1L);
    }
}
