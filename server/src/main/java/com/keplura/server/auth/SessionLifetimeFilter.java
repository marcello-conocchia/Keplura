package com.keplura.server.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/** Applies the absolute session deadline to all endpoints, including /auth. */
@Component
public class SessionLifetimeFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
        var session = request.getSession(false);
        if (session != null && session.getAttribute(AuthenticatedSessions.EXPIRES_AT) instanceof Instant expiry
                && !expiry.isAfter(Instant.now())) {
            session.invalidate();
        }
        chain.doFilter(request, response);
    }
}
