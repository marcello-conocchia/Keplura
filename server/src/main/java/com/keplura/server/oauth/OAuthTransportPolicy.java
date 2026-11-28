package com.keplura.server.oauth;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/**
 * Credentials of personal connections travel over HTTPS only (RFC 6749 §3.1/§3.2, RFC 6750 §5.3): authorization,
 * token and revocation endpoints as well as every resource call that carries a personal bearer token. Checked when
 * the admin saves the configuration AND right before each request, so a target changed later fails closed.
 *
 * <p>{@code keplura.tools.oauth.allow-insecure-loopback-http} (default {@code false}) exists for local test
 * authorization servers only: even when set, plain HTTP is accepted solely for a literal loopback IP
 * ({@code 127.0.0.0/8}, {@code ::1}), never for a host name or any other address.
 */
@Component
public class OAuthTransportPolicy {

    private final boolean insecureLoopback;

    public OAuthTransportPolicy(@Value("${keplura.tools.oauth.allow-insecure-loopback-http:false}") boolean insecureLoopback) {
        this.insecureLoopback = insecureLoopback;
        if (insecureLoopback) {
            LoggerFactory.getLogger(OAuthTransportPolicy.class)
                    .warn("personal OAuth: plain HTTP to literal loopback addresses is allowed (test setting)");
        }
    }

    /** Production policy: HTTPS only. */
    public static OAuthTransportPolicy httpsOnly() { return new OAuthTransportPolicy(false); }

    /** Whether credentials may be sent to this URL. Malformed → false. */
    public boolean allows(String url) {
        if (url == null) return false;
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || uri.getRawUserInfo() != null) return false;
        if (scheme.equals("https")) return true;
        if (!scheme.equals("http") || !insecureLoopback) return false;
        String host = uri.getHost().replace("[", "").replace("]", "");
        if (!host.matches("127(\\.(0|[1-9][0-9]{0,2})){3}") && !host.equals("::1")) return false;
        try {
            return InetAddress.getByName(host).isLoopbackAddress(); // literal: no DNS lookup
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }
}
