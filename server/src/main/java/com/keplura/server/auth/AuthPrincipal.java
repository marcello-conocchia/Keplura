package com.keplura.server.auth;

import java.io.Serializable;

/**
 * The only identity data an authenticated session carries (M2b): a stable user
 * id and display name. Deliberately NOT the JPA {@code User} entity — the
 * session must never hold the password hash, and API responses must never
 * serialise the entity (see the login response type {@link AuthController.LoginResponse}).
 *
 * <p>{@link Serializable} because a servlet container may persist sessions.
 */
public record AuthPrincipal(Long userId, String displayName) implements Serializable {
}
