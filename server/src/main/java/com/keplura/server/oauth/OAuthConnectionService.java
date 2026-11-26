package com.keplura.server.oauth;

import com.keplura.server.admin.SecretCipher;
import com.keplura.server.tool.ToolAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * TU-06b: personal OAuth connections (Authorization Code + PKCE S256) for admin MCP (HTTP) and OpenAPI servers.
 *
 * <ul>
 *   <li>A flow is bound to the starting session (hash), person, organization, server and client revision; state is
 *       stored only as SHA-256, verifier and returned code only encrypted. The callback merely records the code;
 *       the exchange happens when the STARTING session asks for the status, so no other session or person can
 *       complete it.</li>
 *   <li>Tokens are stored per person/org/server encrypted with {@link SecretCipher}; nothing returns them except
 *       {@link #accessToken}, which only adapters call right before the outgoing request. They never reach the model,
 *       cards, audit, logs or API responses.</li>
 *   <li>Any admin change of endpoints, client, scopes or target, a server deletion, leaving the organization or
 *       deleting it removes the connections at once. Without a valid own connection the tool is not offered.</li>
 * </ul>
 */
@Service
public class OAuthConnectionService {

    private static final Logger log = LoggerFactory.getLogger(OAuthConnectionService.class);
    public static final Set<String> KINDS = Set.of("mcp", "openapi");
    public static final Duration FLOW_TTL = Duration.ofMinutes(10);
    /** Renew this long before the stated expiry. */
    static final Duration EARLY_REFRESH = Duration.ofSeconds(30);
    public static final String CALLBACK_PATH = "/api/v1/oauth/connections/callback";

    static final String STARTED = "STARTED";
    static final String RETURNED = "RETURNED";
    static final String DONE = "DONE";
    static final String FAILED = "FAILED";

    public static final String RECONNECT = "Die persönliche Verbindung ist abgelaufen und konnte nicht erneuert werden. "
            + "Bitte unter „Verbindungen“ neu verbinden. Das Werkzeug wurde nicht aufgerufen.";
    public static final String MISSING = "Für dieses Werkzeug fehlt deine persönliche Verbindung. Bitte unter „Verbindungen“ "
            + "verbinden. Das Werkzeug wurde nicht aufgerufen.";

    public record Client(String kind, long serverId, String authorizeUrl, String tokenUrl, String revokeUrl, String clientId,
                         String clientSecretEnc, String scopes, long revision) {
        @Override public String toString() { return "Client[" + kind + ":" + serverId + ", revision " + revision + "]"; }
    }

    /** A usable connection: id and revision only. Tokens stay inside this service. */
    public record Connection(long id, long revision) { }

    public record Started(String flowId, String authorizationUrl) { }

    /** Status of one flow as the starting session sees it. */
    public enum FlowStatus { PENDING, CONNECTED, FAILED, EXPIRED }

    public static class OAuthException extends RuntimeException {
        public OAuthException(String message) { super(message, null, false, false); }
    }

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final OAuthTokenClient tokens;
    private final Supplier<Instant> clock;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<Long, Object> refreshLocks = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public OAuthConnectionService(JdbcTemplate jdbc, SecretCipher cipher, OAuthTokenClient tokens) {
        this(jdbc, cipher, tokens, Instant::now);
    }

    public OAuthConnectionService(JdbcTemplate jdbc, SecretCipher cipher, OAuthTokenClient tokens, Supplier<Instant> clock) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.tokens = tokens;
        this.clock = clock;
    }

    // ---- admin configuration ----

    public Optional<Client> client(String kind, long serverId) {
        if (!KINDS.contains(kind)) return Optional.empty();
        return jdbc.query("""
                select kind, server_id, authorize_url, token_url, revoke_url, client_id, client_secret_enc, scopes, revision
                from tool_oauth_client where kind=? and server_id=?""", (rs, n) -> new Client(rs.getString(1), rs.getLong(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getLong(9)), kind, serverId).stream().findFirst();
    }

    public boolean personal(String kind, long serverId) {
        return client(kind, serverId).isPresent();
    }

    /**
     * Creates or changes the client. {@code clientSecret}: {@code null} = unchanged, "" = remove. Any effective
     * change increments the revision and removes every connection and open flow of this server.
     */
    public Client putClient(String kind, long serverId, String authorizeUrl, String tokenUrl, String revokeUrl,
                            String clientId, String clientSecret, String scopes) {
        var current = client(kind, serverId);
        String secretEnc = current.map(Client::clientSecretEnc).orElse(null);
        if (clientSecret != null) secretEnc = clientSecret.isBlank() ? null : cipher.encrypt(clientSecret.strip());
        String revoke = blankToNull(revokeUrl);
        String scope = blankToNull(scopes);
        Timestamp now = Timestamp.from(clock.get());
        if (current.isEmpty()) {
            jdbc.update("""
                    insert into tool_oauth_client(kind, server_id, authorize_url, token_url, revoke_url, client_id, client_secret_enc,
                      scopes, revision, updated_at) values (?,?,?,?,?,?,?,?,1,?)""", kind, serverId, authorizeUrl.strip(),
                    tokenUrl.strip(), revoke, clientId.strip(), secretEnc, scope, now);
        } else {
            Client c = current.get();
            boolean changed = !c.authorizeUrl().equals(authorizeUrl.strip()) || !c.tokenUrl().equals(tokenUrl.strip())
                    || !Objects.equals(c.revokeUrl(), revoke) || !c.clientId().equals(clientId.strip())
                    || !Objects.equals(c.clientSecretEnc(), secretEnc) || !Objects.equals(c.scopes(), scope);
            if (changed) {
                jdbc.update("""
                        update tool_oauth_client set authorize_url=?, token_url=?, revoke_url=?, client_id=?, client_secret_enc=?,
                          scopes=?, revision=revision+1, updated_at=? where kind=? and server_id=?""", authorizeUrl.strip(),
                        tokenUrl.strip(), revoke, clientId.strip(), secretEnc, scope, now, kind, serverId);
                dropConnections(kind, serverId);
            }
        }
        return client(kind, serverId).orElseThrow();
    }

    /** Server deleted, personal access switched off or the target changed: no old connection may stay usable. */
    public void removeClient(String kind, long serverId) {
        jdbc.update("delete from tool_oauth_client where kind=? and server_id=?", kind, serverId);
        dropConnections(kind, serverId);
    }

    /** Target (URL / base URL) of the server changed: connections of the old target die, the client stays. */
    public void invalidate(String kind, long serverId) {
        int changed = jdbc.update("update tool_oauth_client set revision=revision+1, updated_at=? where kind=? and server_id=?",
                Timestamp.from(clock.get()), kind, serverId);
        if (changed > 0) dropConnections(kind, serverId);
    }

    private void dropConnections(String kind, long serverId) {
        jdbc.update("delete from tool_oauth_connection where kind=? and server_id=?", kind, serverId);
        jdbc.update("delete from tool_oauth_flow where kind=? and server_id=?", kind, serverId);
    }

    public int connectedCount(String kind, long serverId) {
        Integer n = jdbc.queryForObject("""
                select count(*) from tool_oauth_connection c join tool_oauth_client k on k.kind=c.kind and k.server_id=c.server_id
                where c.kind=? and c.server_id=? and c.client_revision=k.revision""", Integer.class, kind, serverId);
        return n == null ? 0 : n;
    }

    /** A person left (or was removed from) an organization. */
    public void removeMember(long userId, long orgId) {
        jdbc.update("delete from tool_oauth_connection where user_id=? and org_id=?", userId, orgId);
        jdbc.update("delete from tool_oauth_flow where user_id=? and org_id=?", userId, orgId);
    }

    public void removeOrg(long orgId) {
        jdbc.update("delete from tool_oauth_connection where org_id=?", orgId);
        jdbc.update("delete from tool_oauth_flow where org_id=?", orgId);
    }

    // ---- connections as the adapters see them ----

    /**
     * The person's usable connection for this server, or empty. Usable = current client revision and either a token
     * that is not yet expired or a refresh token. Never returns token material.
     */
    public Optional<Connection> connection(long userId, long orgId, String kind, long serverId) {
        Instant now = clock.get();
        return jdbc.query("""
                select c.id, c.client_revision, c.expires_at, c.refresh_token_enc is not null from tool_oauth_connection c
                join tool_oauth_client k on k.kind=c.kind and k.server_id=c.server_id and k.revision=c.client_revision
                where c.user_id=? and c.org_id=? and c.kind=? and c.server_id=?
                and exists(select 1 from membership m where m.user_id=c.user_id and m.org_id=c.org_id)""", (rs, n) -> {
            Timestamp expires = rs.getTimestamp(3);
            boolean live = expires == null || expires.toInstant().isAfter(now) || rs.getBoolean(4);
            return live ? new Connection(rs.getLong(1), rs.getLong(2)) : null;
        }, userId, orgId, kind, serverId).stream().filter(Objects::nonNull).findFirst();
    }

    /**
     * Access token for exactly this connection, renewed by the server when it is (about to be) expired. Throws
     * {@link ToolAdapter.ToolNotStartedException} with a fixed text when the connection is gone, was replaced or
     * cannot be renewed; a renewal failure removes the connection, so the tool is no longer offered.
     */
    public String accessToken(long userId, long orgId, String kind, long serverId, long connectionId) {
        Object lock = refreshLocks.computeIfAbsent(connectionId, k -> new Object());
        synchronized (lock) {
            try {
                var row = tokenRow(userId, orgId, kind, serverId);
                if (row == null || row.id() != connectionId) throw new ToolAdapter.ToolNotStartedException(MISSING);
                Instant now = clock.get();
                if (row.expiresAt() == null || row.expiresAt().minus(EARLY_REFRESH).isAfter(now)) {
                    return decrypt(row.accessEnc(), connectionId);
                }
                if (row.refreshEnc() == null) {
                    drop(connectionId);
                    throw new ToolAdapter.ToolNotStartedException(RECONNECT);
                }
                Client client = client(kind, serverId).orElseThrow(() -> new ToolAdapter.ToolNotStartedException(MISSING));
                OAuthTokenClient.Tokens fresh;
                try {
                    fresh = tokens.refresh(client.tokenUrl(), client.clientId(), secret(client),
                            decrypt(row.refreshEnc(), connectionId), client.scopes());
                } catch (OAuthTokenClient.TokenException e) {
                    log.warn("personal oauth renewal failed (connection={}, rejected={})", connectionId, e.rejected());
                    drop(connectionId);
                    throw new ToolAdapter.ToolNotStartedException(RECONNECT);
                }
                int updated = jdbc.update("""
                        update tool_oauth_connection set access_token_enc=?, refresh_token_enc=?, expires_at=?, updated_at=?
                        where id=? and client_revision=(select revision from tool_oauth_client where kind=? and server_id=?)""",
                        cipher.encrypt(fresh.accessToken()), fresh.refreshToken() == null ? null : cipher.encrypt(fresh.refreshToken()),
                        expiry(fresh, now), Timestamp.from(now), connectionId, kind, serverId);
                if (updated != 1) throw new ToolAdapter.ToolNotStartedException(MISSING); // revoked while renewing
                return fresh.accessToken();
            } finally {
                refreshLocks.remove(connectionId, lock);
            }
        }
    }

    private record TokenRow(long id, String accessEnc, String refreshEnc, Instant expiresAt) { }

    private TokenRow tokenRow(long userId, long orgId, String kind, long serverId) {
        return jdbc.query("""
                select c.id, c.access_token_enc, c.refresh_token_enc, c.expires_at from tool_oauth_connection c
                join tool_oauth_client k on k.kind=c.kind and k.server_id=c.server_id and k.revision=c.client_revision
                where c.user_id=? and c.org_id=? and c.kind=? and c.server_id=?
                and exists(select 1 from membership m where m.user_id=c.user_id and m.org_id=c.org_id)""",
                (rs, n) -> new TokenRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()),
                userId, orgId, kind, serverId).stream().findFirst().orElse(null);
    }

    private String decrypt(String enc, long connectionId) {
        try {
            return cipher.decrypt(enc);
        } catch (RuntimeException e) {
            log.warn("personal oauth token unreadable (connection={})", connectionId);
            throw new ToolAdapter.ToolNotStartedException(RECONNECT);
        }
    }

    private void drop(long connectionId) {
        jdbc.update("delete from tool_oauth_connection where id=?", connectionId);
    }

    private String secret(Client client) {
        if (client.clientSecretEnc() == null) return null;
        try { return cipher.decrypt(client.clientSecretEnc()); }
        catch (RuntimeException e) { throw new OAuthException("Der OAuth-Client ist nicht lesbar."); }
    }

    private static Timestamp expiry(OAuthTokenClient.Tokens t, Instant now) {
        return t.expiresIn() == null ? null : Timestamp.from(now.plusSeconds(Math.min(t.expiresIn(), 365L * 24 * 3600)));
    }

    /** Person disconnects: tokens are deleted first (effective at once), then revoked best effort. */
    public boolean disconnect(long userId, long orgId, String kind, long serverId) {
        if (!KINDS.contains(kind)) return false;
        var rows = jdbc.query("select id, access_token_enc, refresh_token_enc from tool_oauth_connection where user_id=? and org_id=? and kind=? and server_id=?",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, userId, orgId, kind, serverId);
        jdbc.update("delete from tool_oauth_connection where user_id=? and org_id=? and kind=? and server_id=?", userId, orgId, kind, serverId);
        jdbc.update("delete from tool_oauth_flow where user_id=? and org_id=? and kind=? and server_id=?", userId, orgId, kind, serverId);
        if (rows.isEmpty()) return false;
        var client = client(kind, serverId);
        if (client.isPresent() && client.get().revokeUrl() != null) {
            String[] row = rows.getFirst();
            try {
                boolean refresh = row[2] != null;
                String token = cipher.decrypt(refresh ? row[2] : row[1]);
                tokens.revoke(client.get().revokeUrl(), client.get().clientId(), secret(client.get()), token,
                        refresh ? "refresh_token" : "access_token");
            } catch (RuntimeException e) {
                log.warn("personal oauth revocation skipped ({})", e.getClass().getSimpleName());
            }
        }
        return true;
    }

    // ---- authorization flow ----

    /** Starts a flow for the person; the caller has already proven org membership and server availability. */
    public Started start(long userId, long orgId, String kind, long serverId, String sessionId, String redirectUri, String resource) {
        Client client = client(kind, serverId).orElseThrow(() -> new OAuthException("Für diesen Server ist keine persönliche Verbindung eingerichtet."));
        if (!cipher.available()) throw new OAuthException("Verbindungen können nicht gespeichert werden: KEPLURA_SECRETS_MASTER_KEY fehlt.");
        String state = randomUrlSafe(32);
        String verifier = randomUrlSafe(48);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256Bytes(verifier));
        String flowId = UUID.randomUUID().toString();
        Instant now = clock.get();
        // One open attempt per person and server: a new start replaces the old one.
        jdbc.update("delete from tool_oauth_flow where user_id=? and org_id=? and kind=? and server_id=?", userId, orgId, kind, serverId);
        jdbc.update("""
                insert into tool_oauth_flow(public_id, state_hash, session_hash, user_id, org_id, kind, server_id, client_revision,
                  verifier_enc, redirect_uri, status, created_at, expires_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                flowId, sha256Hex(state), sha256Hex(sessionId), userId, orgId, kind, serverId, client.revision(),
                cipher.encrypt(verifier), redirectUri, STARTED, Timestamp.from(now), Timestamp.from(now.plus(FLOW_TTL)));
        StringBuilder url = new StringBuilder(client.authorizeUrl()).append(client.authorizeUrl().contains("?") ? '&' : '?');
        url.append("response_type=code&client_id=").append(enc(client.clientId()))
                .append("&redirect_uri=").append(enc(redirectUri))
                .append("&state=").append(state)
                .append("&code_challenge=").append(challenge).append("&code_challenge_method=S256");
        if (client.scopes() != null) url.append("&scope=").append(enc(client.scopes()));
        if (resource != null) url.append("&resource=").append(enc(resource));
        return new Started(flowId, url.toString());
    }

    /**
     * The authorization server redirected the browser back. Only records the code for the matching, fresh,
     * unconsumed flow. {@code browserUserId}: the person of a Keplura session in that browser, if any — a different
     * person ends the flow. Returns whether the code was accepted.
     */
    public boolean callback(String state, String code, String error, Long browserUserId) {
        if (state == null || state.isEmpty() || state.length() > 200) return false;
        var flows = jdbc.query("select id, user_id, status, expires_at from tool_oauth_flow where state_hash=?",
                (rs, n) -> new Object[]{rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getTimestamp(4).toInstant()}, sha256Hex(state));
        if (flows.isEmpty()) return false;
        long id = (long) flows.getFirst()[0];
        long owner = (long) flows.getFirst()[1];
        boolean fresh = ((Instant) flows.getFirst()[3]).isAfter(clock.get());
        boolean ok = fresh && error == null && code != null && !code.isEmpty() && code.length() <= 4000
                && (browserUserId == null || browserUserId == owner);
        if (!ok) {
            jdbc.update("update tool_oauth_flow set status=?, code_enc=null where id=? and status in (?, ?)", FAILED, id, STARTED, RETURNED);
            return false;
        }
        // Single use: only a STARTED flow takes a code; a second callback with the same state fails the flow.
        int taken = jdbc.update("update tool_oauth_flow set status=?, code_enc=? where id=? and status=?", RETURNED, cipher.encrypt(code), id, STARTED);
        if (taken != 1) {
            jdbc.update("update tool_oauth_flow set status=?, code_enc=null where id=? and status=?", FAILED, id, RETURNED);
            return false;
        }
        return true;
    }

    /**
     * Status for the starting session. A returned code is exchanged exactly once here; the tokens are bound to the
     * flow's person, organization, server and revision. Unknown flow or other session/person → empty.
     */
    public Optional<FlowStatus> status(long userId, long orgId, String flowId, String sessionId) {
        var rows = jdbc.query("""
                select id, kind, server_id, client_revision, verifier_enc, redirect_uri, code_enc, status, expires_at, session_hash
                from tool_oauth_flow where public_id=? and user_id=? and org_id=?""", (rs, n) -> new Object[]{rs.getLong(1),
                rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getTimestamp(9).toInstant(), rs.getString(10)}, flowId, userId, orgId);
        if (rows.isEmpty() || !MessageDigest.isEqual(((String) rows.getFirst()[9]).getBytes(StandardCharsets.US_ASCII),
                sha256Hex(sessionId).getBytes(StandardCharsets.US_ASCII))) return Optional.empty();
        Object[] r = rows.getFirst();
        long id = (long) r[0];
        String kind = (String) r[1];
        long serverId = (long) r[2];
        String status = (String) r[7];
        if (DONE.equals(status)) return Optional.of(FlowStatus.CONNECTED);
        if (FAILED.equals(status)) return Optional.of(FlowStatus.FAILED);
        if (!((Instant) r[8]).isAfter(clock.get())) {
            jdbc.update("update tool_oauth_flow set status=?, code_enc=null where id=?", FAILED, id);
            return Optional.of(FlowStatus.EXPIRED);
        }
        if (STARTED.equals(status)) return Optional.of(FlowStatus.PENDING);
        if (!RETURNED.equals(status)) return Optional.of(FlowStatus.FAILED);
        // Claim the exchange exactly once.
        if (jdbc.update("update tool_oauth_flow set status=?, code_enc=null where id=? and status=?", "EXCHANGING", id, RETURNED) != 1) {
            return Optional.of(FlowStatus.PENDING);
        }
        String codeEnc = (String) r[6];
        var client = client(kind, serverId);
        if (client.isEmpty() || client.get().revision() != (long) r[3]) return failed(id);
        OAuthTokenClient.Tokens got;
        try {
            got = tokens.exchange(client.get().tokenUrl(), client.get().clientId(), secret(client.get()), cipher.decrypt(codeEnc),
                    (String) r[5], cipher.decrypt((String) r[4]));
        } catch (OAuthTokenClient.TokenException e) {
            log.warn("personal oauth code exchange failed (rejected={})", e.rejected());
            return failed(id);
        } catch (RuntimeException e) {
            return failed(id);
        }
        Instant now = clock.get();
        jdbc.update("delete from tool_oauth_connection where user_id=? and org_id=? and kind=? and server_id=?", userId, orgId, kind, serverId);
        int stored = jdbc.update("""
                insert into tool_oauth_connection(user_id, org_id, kind, server_id, client_revision, access_token_enc, refresh_token_enc,
                  expires_at, created_at, updated_at)
                select ?,?,?,?,?,?,?,?,?,? from tool_oauth_client k where k.kind=? and k.server_id=? and k.revision=?
                and exists(select 1 from membership m where m.user_id=? and m.org_id=?)""",
                userId, orgId, kind, serverId, r[3], cipher.encrypt(got.accessToken()),
                got.refreshToken() == null ? null : cipher.encrypt(got.refreshToken()), expiry(got, now), Timestamp.from(now),
                Timestamp.from(now), kind, serverId, r[3], userId, orgId);
        if (stored != 1) return failed(id);
        jdbc.update("update tool_oauth_flow set status=?, verifier_enc='-' where id=?", DONE, id);
        return Optional.of(FlowStatus.CONNECTED);
    }

    private Optional<FlowStatus> failed(long flowId) {
        jdbc.update("update tool_oauth_flow set status=?, code_enc=null where id=?", FAILED, flowId);
        return Optional.of(FlowStatus.FAILED);
    }

    /** Expired or finished flows are removed (never a token in there; only hashes and encrypted one-time values). */
    public void sweepFlows() {
        jdbc.update("delete from tool_oauth_flow where expires_at < ?", Timestamp.from(clock.get().minus(FLOW_TTL)));
    }

    // ---- helpers ----

    private String randomUrlSafe(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static byte[] sha256Bytes(String s) {
        try { return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static String sha256Hex(String s) {
        return HexFormat.of().formatHex(sha256Bytes(s == null ? "" : s));
    }

    private static String enc(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.strip(); }

    /** Fingerprint part of a personal tool: client revision and the person's connection id. */
    public static String fingerprintPart(Connection c) {
        return ":oauth:" + c.revision() + ":" + c.id();
    }
}
