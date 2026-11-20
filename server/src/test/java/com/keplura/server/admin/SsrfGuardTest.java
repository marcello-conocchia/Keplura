package com.keplura.server.admin;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADMIN-KI Scope E (docs/adr/0008 §7): the SSRF policy is default-closed for
 * everything non-public — loopback, RFC1918, link-local, multicast — and the
 * ONLY way a LAN host becomes dialable is an explicit CIDR in
 * keplura.admin.ssrf-allow-cidrs. Shape failures (non-http schemes, userinfo,
 * file://) never produce a dialable URI.
 */
class SsrfGuardTest {

    private static SsrfGuard closed() {
        return new SsrfGuard("");
    }

    @Test
    void loopbackAndRfc1918AndLinkLocalAreRefusedWithEmptyWhitelist() {
        SsrfGuard guard = closed();
        for (String url : new String[]{
                "http://127.0.0.1:11434/v1",
                "http://localhost:8000/v1",
                "http://10.0.0.5:8000/v1",
                "http://192.168.178.32:11434/v1",
                "http://172.16.0.9/v1",
                "http://169.254.169.254/latest/meta-data", // cloud metadata endpoint
                "http://[::1]:11434/v1"}) {
            assertThatThrownBy(() -> guard.validateForDial(url))
                    .as(url)
                    .isInstanceOfSatisfying(AdminException.class,
                            e -> assertThat(e.code()).isEqualTo(AdminException.TARGET_REFUSED));
        }
    }

    /**
     * Honest scope note: without a DNS server this leg can only pin the
     * IP-literal refusals above. A hostname that RESOLVES to 127.0.0.1
     * (dns rebinding style) takes the same route-policy code path —
     * routeAllowed(resolvedAddress) — which the next leg pins directly.
     */
    @Test
    void resolvedRoutePolicyIsTheSingleGateForHostnames() throws UnknownHostException {
        SsrfGuard guard = closed();
        assertThat(guard.routeAllowed(InetAddress.getByName("127.0.0.53"))).isFalse();
        assertThat(guard.routeAllowed(InetAddress.getByName("10.1.2.3"))).isFalse();
        assertThat(guard.routeAllowed(InetAddress.getByName("8.8.8.8"))).isTrue();
    }

    /**
     * Review-Runde-1 (blocking finding): Java's isSiteLocalAddress covers
     * only the DEPRECATED fec0::/10 — fc00::/7 (RFC 4193 ULA) falls through
     * unless checked explicitly. Upper-Bit-Test: 0xfc AND 0xfd both hit.
     */
    @Test
    void ipv6UniqueLocalFc00Slash7IsRefused() throws UnknownHostException {
        SsrfGuard guard = closed();
        assertThat(guard.routeAllowed(InetAddress.getByName("fc00::1"))).isFalse();
        assertThat(guard.routeAllowed(InetAddress.getByName("fd12:3456::789a"))).isFalse();
        // boundaries of the /7: fc00:: and fdff:: are ULA, fe00:: is NOT
        // (fe80::/10 link-local is covered by isLinkLocalAddress, fec0::/10
        // legacy site-local by isSiteLocalAddress — both already refused).
        assertThat(guard.routeAllowed(InetAddress.getByName("fe80::1"))).isFalse(); // link-local leg
        // a genuine global v6 address must stay dialable (no over-block):
        assertThat(guard.routeAllowed(InetAddress.getByName("2001:4860:4860::8888"))).isTrue();
    }

    /**
     * Review-Runde-1 (added decision, "default dicht" posture): CGNAT
     * 100.64.0.0/10 (RFC 6598) is carrier-grade private space that
     * isSiteLocalAddress misses — refused by default like RFC1918, openable
     * only via an explicit operator CIDR.
     */
    @Test
    void cgnat100_64Slash10IsRefusedByDefault() throws UnknownHostException {
        SsrfGuard guard = closed();
        assertThat(guard.routeAllowed(InetAddress.getByName("100.64.0.1"))).isFalse();
        assertThat(guard.routeAllowed(InetAddress.getByName("100.127.255.255"))).isFalse();
        // just outside the /10 stays global (no over-block):
        assertThat(guard.routeAllowed(InetAddress.getByName("100.63.255.255"))).isTrue();
        assertThat(guard.routeAllowed(InetAddress.getByName("100.128.0.1"))).isTrue();
        // and the dial path refuses an IP-literal into CGNAT as well:
        assertThatThrownBy(() -> guard.validateForDial("http://100.64.0.1:11434/v1"))
                .isInstanceOfSatisfying(AdminException.class,
                        e -> assertThat(e.code()).isEqualTo(AdminException.TARGET_REFUSED));
        // an operator may still open it deliberately:
        assertThat(new SsrfGuard("100.64.0.0/10").routeAllowed(InetAddress.getByName("100.64.0.1")))
                .isTrue();
    }

    @Test
    void explicitCidrWhitelistOpensOnlyThatRange() {
        SsrfGuard guard = new SsrfGuard("192.168.178.0/24, 10.7.7.7/32");
        assertThat(guard.routeAllowed(address("192.168.178.32"))).isTrue();
        assertThat(guard.routeAllowed(address("192.168.179.32"))).isFalse(); // next /24 stays closed
        assertThat(guard.routeAllowed(address("10.7.7.7"))).isTrue();
        assertThat(guard.routeAllowed(address("10.7.7.8"))).isFalse();
        assertThatThrownBy(() -> guard.validateForDial("http://127.0.0.1:11434/v1"))
                .isInstanceOf(AdminException.class); // loopback stays closed even with a whitelist
    }

    @Test
    void malformedWhitelistFailsTheBootLoudly() {
        assertThatThrownBy(() -> new SsrfGuard("not-a-cidr"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unparsable");
        assertThatThrownBy(() -> new SsrfGuard("192.168.0.0/33"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nonHttpSchemesAndUserinfoAndBareHostsAreShapeRefusals() {
        SsrfGuard guard = closed();
        for (String url : new String[]{
                "file:///etc/passwd",
                "gopher://example.com/x",
                "http://user:pass@public.example.com/v1",
                "not a url at all",
                "http://",
                "https://"}) {
            assertThatThrownBy(() -> guard.validateForDial(url))
                    .as(url)
                    .isInstanceOf(AdminException.class);
        }
    }

    @Test
    void httpsWithTrailingSlashAndPathPassShapeValidation() {
        // shape parse must NOT depend on the route (the route half needs DNS):
        assertThatCode(() -> SsrfGuard.parseShape("http://public.example.com/v1/"))
                .doesNotThrowAnyException();
    }

    private static InetAddress address(String ip) {
        try {
            return InetAddress.getByName(ip);
        } catch (UnknownHostException e) {
            throw new AssertionError(e);
        }
    }
}
