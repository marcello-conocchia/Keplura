package com.keplura.server.auth;

import com.keplura.server.identity.PasswordHasher;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M2b: unit-level behaviour of {@link AuthService} — mocked repository, real
 * Argon2 hasher. The response-shape / wire-level guarantees live in
 * AuthApiIntegrationTest; here we pin the authentication decision itself.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    private PasswordHasher hasher;
    private AuthService service;

    @BeforeEach
    void setUp() {
        hasher = spy(new PasswordHasher());
        service = new AuthService(userRepository, hasher);
    }

    @Test
    void correctCredentialsYieldPrincipalWithIdAndDisplayNameOnly() {
        User user = User.create("zed@example.com", hasher.hash("top-secret"), "Zed");
        // id is normally assigned by JPA; inject it for the assertion
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", 42L);
        when(userRepository.findByEmail("zed@example.com")).thenReturn(Optional.of(user));

        Optional<AuthPrincipal> principal = service.authenticate("Zed@Example.COM ", "top-secret");

        assertThat(principal).isPresent();
        assertThat(principal.get().userId()).isEqualTo(42L);
        assertThat(principal.get().displayName()).isEqualTo("Zed");
        // the principal must never carry the hash
        assertThat(principal.toString()).doesNotContain("argon2").doesNotContain("top-secret");
    }

    @Test
    void wrongPasswordYieldsEmpty() {
        User user = User.create("kim@example.com", hasher.hash("real-pw"), "Kim");
        when(userRepository.findByEmail("kim@example.com")).thenReturn(Optional.of(user));

        assertThat(service.authenticate("kim@example.com", "guess")).isEmpty();
    }

    @Test
    void unknownEmailPaysADummyHashVerifyAgainstTheRealPath() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThat(service.authenticate("ghost@example.com", "anything")).isEmpty();

        // Anti-enumeration: an Argon2 verify must have actually run even though
        // no user exists (dummy hash). Exactly one verify call, against a hash
        // that is NOT derived from any stored user.
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(hasher, times(1)).verify(anyString(), hashCaptor.capture());
        assertThat(hashCaptor.getValue()).startsWith("$argon2id$");
    }

    @Test
    void lookupUsesTheNormalizedEmail() {
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.empty());

        service.authenticate("  ADA@Example.com ", "x");

        verify(userRepository).findByEmail("ada@example.com");
    }
}
