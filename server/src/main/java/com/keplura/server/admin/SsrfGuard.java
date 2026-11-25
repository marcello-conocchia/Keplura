package com.keplura.server.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SSRF policy for the ADMIN-KI connection test (Scope E, docs/adr/0008 §7) —
 * the one place where a URL enters the system through a validation path that
 * did not exist before ("test before save"). The actual chat/embedding
 * traffic keeps dialing through the established GatewayClient exactly as the
 * ADR §7.5 words it: that risk surface is already bounded by the admin role
 * that owns the stored rows. Provider CREATE/EDIT validates shape only
 * (scheme/userinfo/port) — a loopback Ollama must stay saveable out of the
 * box; the DIAL-side route policy below guards the active probe.
 *
 * <p>Rules, in order (ADR §7.1–7.2):
 * <ol>
 *   <li>Shape via {@link #parseShape}: absolute http/https, no userinfo, sane
 *       port. https always; http stays legal because the product's own
 *       presets dial LAN/loopback Ollama and vLLM over plain HTTP.</li>
 *   <li>DNS is RESOLVED and every returned address must pass the route check
 *       — no blind dial. Loopback, link-local (cloud metadata!), site-local
 *       (RFC1918), CGNAT 100.64.0.0/10, any-local, multicast and IPv6
 *       unique-local fc00::/7 are ALL refused by default ("default dicht"
 *       posture, ADR-0008 §7.2): CGNAT is carrier-grade private space
 *       (twin of RFC1918) and Java's isSiteLocalAddress covers neither it
 *       nor fc00::/7 (only the deprecated fec0::/10), so BOTH need the
 *       explicit checks in {@link #routeAllowed}.</li>
 *   <li>Ops open specific targets through
 *       {@code keplura.admin.ssrf-allow-cidrs} (ENV, comma list of IPv4
 *       CIDRs). Empty default = fully closed, exactly as the ADR words it;
 *       a single-machine Ollama deployment that wants a green connection
 *       test sets 127.0.0.0/8 there. Entries are operator statements, never
 *       admin-UI input.</li>
 *   <li>DNS failure is a refusal with the SAME outcome as a policy refusal
 *       (uniform {@link AdminException#targetRefused()}), so the surface
 *       doubles as neither an internal-DNS oracle nor a port scanner.</li>
 * </ol>
 *
 * <p>TOCTOU caveat, documented honestly: the checked resolution and the
 * socket's own connection are separate lookups — a hostile DNS could answer
 * differently in between. The threat model here is an authenticated ADMIN
 * configuring their own infrastructure, not an untrusted input; the guards
 * that matter in this model (userinfo ban, Redirect.NEVER at dial time, hard
 * timeouts) are enforced where the bytes actually flow. Raw-IP-pinned
 * sockets are the documented next step should this ever face lower trust.
 */
@Service
public class SsrfGuard {

    private static final Logger log = LoggerFactory.getLogger(SsrfGuard.class);

    /** Operator-declared IPv4 ranges that route checks may additionally allow. */
    private final List<long[]> allowedCidrs; // [networkLong, prefixLen]

    public SsrfGuard(@Value("${keplura.admin.ssrf-allow-cidrs:}") String cidrsCsv) {
        this.allowedCidrs = parseCidrs(cidrsCsv);
        if (!allowedCidrs.isEmpty()) {
            log.info("SSRF allow-list active with {} operator CIDR(s)", allowedCidrs.size());
        }
    }

    /**
     * Shape + route validation of a dial target. Throws
     * {@link AdminException} — target refusals are always uniform
     * targetRefused(), never naming which rule or address fired.
     */
    public void validateForDial(String rawUrl) {
        URI uri = parseShape(rawUrl);
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw AdminException.targetRefused();
        }
        for (InetAddress addr : addresses) {
            if (!routeAllowed(addr)) {
                // NOTHING identifying in the log: neither host nor address —
                // a probing admin (or a stolen session) learns only "refused".
                log.warn("SSRF guard refused a dial target (policy veto)");
                throw AdminException.targetRefused();
            }
        }
    }

    /** Pure shape check (no DNS). 400-class refusal for malformed input. */
    public static URI parseShape(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw AdminException.invalid("base URL is required");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw AdminException.invalid("base URL is malformed");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw AdminException.invalid("base URL must be http(s)");
        }
        if (uri.getUserInfo() != null) {
            throw AdminException.invalid("base URL must not carry credentials");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw AdminException.invalid("base URL must name a host");
        }
        int port = uri.getPort();
        if (port == 0 || port > 65535) {
            throw AdminException.invalid("base URL port is invalid");
        }
        return uri;
    }

    /** Route policy for one resolved address (package-visible for unit tests). */
    boolean routeAllowed(InetAddress addr) {
        if (explicitlyAllowed(addr)) {
            return true;
        }
        return publicRouteAllowed(addr);
    }

    /** Untrusted page URLs never inherit operator exceptions for configured admin targets. */
    public static boolean publicRouteAllowed(InetAddress addr) {
        if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
            return false;
        }
        if (isCgnat(addr) || isUniqueLocalV6(addr)) {
            return false; // spaces Java's isSiteLocalAddress does NOT cover
        }
        return true; // a global routable address
    }

    /**
     * RFC 6598 CGNAT 100.64.0.0/10 — carrier-grade private space, the twin of
     * RFC1918 that {@link InetAddress#isSiteLocalAddress()} misses. Refused by
     * default ("default dicht", ADR-0008 §7.2); ops open it per CIDR if ever
     * genuinely needed.
     */
    private static boolean isCgnat(InetAddress addr) {
        byte[] b = addr.getAddress();
        return b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 0x40;
    }

    /**
     * RFC 4193 IPv6 unique-local fc00::/7. Java's isSiteLocalAddress only
     * knows the DEPRECATED fec0::/10, so fc00::/7 would otherwise fall
     * through to "global". Upper-Bit-Test on byte[0]: 0xfc/0xfd → (b & 0xfe)
     * == 0xfc.
     */
    private static boolean isUniqueLocalV6(InetAddress addr) {
        byte[] b = addr.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }

    private boolean explicitlyAllowed(InetAddress addr) {
        if (allowedCidrs.isEmpty()) {
            return false;
        }
        byte[] bytes = addr.getAddress();
        if (bytes.length != 4) {
            return false; // the allow-list is IPv4 (documented first-round limit)
        }
        long ip = 0;
        for (byte b : bytes) {
            ip = (ip << 8) | (b & 0xff);
        }
        for (long[] cidr : allowedCidrs) {
            int prefix = (int) cidr[1];
            long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            if ((ip & mask) == (cidr[0] & mask)) {
                return true;
            }
        }
        return false;
    }

    static List<long[]> parseCidrs(String csv) {
        List<long[]> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        for (String token : csv.split(",")) {
            String t = token.trim();
            if (!t.isEmpty()) {
                out.add(parseCidr(t));
            }
        }
        return List.copyOf(out);
    }

    private static long[] parseCidr(String cidr) {
        try {
            String[] parts = cidr.split("/", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("allow-cidr needs <ipv4>/<prefix>: " + cidr);
            }
            byte[] bytes = InetAddress.getByName(parts[0]).getAddress();
            int prefix = Integer.parseInt(parts[1]);
            if (bytes.length != 4 || prefix < 0 || prefix > 32) {
                throw new IllegalArgumentException("allow-cidr must be IPv4 with /0..32: " + cidr);
            }
            long ip = 0;
            for (byte b : bytes) {
                ip = (ip << 8) | (b & 0xff);
            }
            return new long[]{ip, prefix};
        } catch (UnknownHostException | IllegalArgumentException e) {
            // A malformed allow-list is a deployment bug: fail the boot loudly
            // (same posture as an unknown provider name) rather than dial wide.
            throw new IllegalStateException("unparsable keplura.admin.ssrf-allow-cidrs entry '"
                    + cidr + "'", e);
        }
    }
}
