package com.keplura.server.websearch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Websuche für den Chat (PO-Auftrag 14.11.2026, „wie bei Open WebUI, nur besser").
 *
 * <p>Anbieter per Server-Konfiguration: {@code searxng} (selbst gehostet, empfohlen —
 * Suchanfragen verlassen das Haus nur über die eigene Instanz), {@code brave},
 * {@code tavily}; {@code none} = aus (Standard). Verwendet werden NUR Titel, Link und
 * Auszug der Treffer — der Server ruft keine Ergebnisseiten ab (kein SSRF-Pfad ins
 * Firmennetz). Der API-Schlüssel steht nur in der Umgebung, nie in Antworten oder Logs.
 */
@Service
public class WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);

    public record WebResult(String title, String url, String snippet) {
    }

    public static class WebSearchException extends RuntimeException {
        public WebSearchException(String message) {
            super(message);
        }
    }

    static final int MAX_QUERY = 400;
    static final int MAX_SNIPPET = 600;

    /** Wirksame Einstellung: Administration (DB) vor Server-Konfiguration. */
    public record Config(String provider, String baseUrl, String apiKey, int results, List<String> blockedDomains,
                         String source) {
        public boolean enabled() {
            return switch (provider) {
                case "searxng" -> !baseUrl.isEmpty();
                case "brave", "tavily" -> !apiKey.isEmpty();
                default -> false;
            };
        }
    }

    static final java.util.Set<String> PROVIDERS = java.util.Set.of("none", "searxng", "brave", "tavily");

    private com.keplura.server.admin.SsrfGuard guard = new com.keplura.server.admin.SsrfGuard("");

    @org.springframework.beans.factory.annotation.Autowired
    void setGuard(com.keplura.server.admin.SsrfGuard guard) { this.guard = guard; }

    private final Config envConfig;
    private final HttpClient http;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private com.keplura.server.admin.SecretCipher cipher;

    public WebSearchService(@Value("${keplura.websearch.provider:none}") String provider,
                            @Value("${keplura.websearch.url:}") String baseUrl,
                            @Value("${keplura.websearch.api-key:}") String apiKey,
                            @Value("${keplura.websearch.results:5}") int results,
                            @Value("${keplura.websearch.blocked-domains:}") String blockedDomains,
                            @Value("${keplura.websearch.timeout-seconds:8}") int timeoutSeconds) {
        this.envConfig = config(provider, baseUrl, apiKey, results, blockedDomains, "env");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setStore(org.springframework.jdbc.core.JdbcTemplate jdbc, com.keplura.server.admin.SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    static Config config(String provider, String baseUrl, String apiKey, int results, String blocked, String source) {
        String p = provider == null ? "none" : provider.strip().toLowerCase(Locale.ROOT);
        if (!PROVIDERS.contains(p)) p = "none";
        List<String> domains = blocked == null || blocked.isBlank() ? List.of()
                : java.util.Arrays.stream(blocked.split("[,\\s]+")).map(x -> x.strip().toLowerCase(Locale.ROOT))
                    .filter(x -> !x.isEmpty()).toList();
        return new Config(p, baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", ""),
                apiKey == null ? "" : apiKey.strip(), Math.max(1, Math.min(results, 10)), domains, source);
    }

    /** Liest bei jedem Aufruf frisch (eine Zeile) — Änderungen wirken ohne Neustart. */
    public Config effective() {
        if (jdbc == null) return envConfig;
        List<Config> rows = jdbc.query("select provider, url, api_key_enc, results, blocked_domains from web_search_settings where id = 1",
                (rs, n) -> {
                    String enc = rs.getString(3);
                    String key = "";
                    if (enc != null && !enc.isEmpty() && cipher != null && cipher.available()) {
                        try {
                            key = cipher.decrypt(enc);
                        } catch (RuntimeException e) {
                            log.warn("web search key could not be decrypted (cause={})", e.getClass().getSimpleName());
                        }
                    }
                    return config(rs.getString(1), rs.getString(2), key, rs.getInt(4), rs.getString(5), "admin");
                });
        return rows.isEmpty() ? envConfig : rows.get(0);
    }

    public boolean enabled() {
        return effective().enabled();
    }

    public String providerName() {
        Config c = effective();
        return c.enabled() ? c.provider() : "none";
    }

    public List<WebResult> search(String rawQuery) {
        return search(effective(), rawQuery);
    }

    public List<WebResult> search(Config c, String rawQuery) {
        if (!c.enabled()) return List.of();
        String q = rawQuery == null ? "" : rawQuery.strip();
        if (q.isEmpty()) return List.of();
        if (q.length() > MAX_QUERY) q = q.substring(0, MAX_QUERY);
        try {
            List<WebResult> out = switch (c.provider()) {
                case "searxng" -> searxng(c, q);
                case "brave" -> brave(c, q);
                case "tavily" -> tavily(c, q);
                default -> List.of();
            };
            return out.stream().filter(r -> isAllowed(c, r.url())).limit(c.results()).toList();
        } catch (WebSearchException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebSearchException("web search interrupted");
        } catch (Exception e) {
            log.warn("web search failed (provider={}, cause={})", c.provider(), e.getClass().getSimpleName());
            throw new WebSearchException("web search is unavailable");
        }
    }

    private List<WebResult> searxng(Config c, String q) throws Exception {
        URI base = com.keplura.server.admin.SsrfGuard.parseShape(c.baseUrl());
        if (base.getRawQuery() != null || base.getRawFragment() != null) {
            throw new WebSearchException("web search is unavailable");
        }
        String url = base.toASCIIString().replaceAll("/+$", "") + "/search?format=json&q=" + enc(q);
        guard.validateForDial(url); // the final target, including operator CIDR policy
        JsonNode root = get(url, null, null);
        return collect(root.path("results"), "title", "url", "content");
    }

    private List<WebResult> brave(Config c, String q) throws Exception {
        JsonNode root = get("https://api.search.brave.com/res/v1/web/search?count=" + c.results() + "&q=" + enc(q),
                "X-Subscription-Token", c.apiKey());
        return collect(root.path("web").path("results"), "title", "url", "description");
    }

    private List<WebResult> tavily(Config c, String q) throws Exception {
        String body = mapper.writeValueAsString(java.util.Map.of("query", q, "max_results", c.results()));
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.tavily.com/search"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + c.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return collect(send(req).path("results"), "title", "url", "content");
    }

    private JsonNode get(String url, String header, String value) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json").GET();
        if (header != null) b.header(header, value);
        return send(b.build());
    }

    private JsonNode send(HttpRequest req) throws Exception {
        HttpResponse<java.io.InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = new com.keplura.server.http.BoundedHttpBody(r.body(), 2_000_000, Duration.ofSeconds(15))) {
            if (r.statusCode() != 200) {
                log.warn("web search provider answered {}", r.statusCode());
                throw new WebSearchException("web search is unavailable");
            }
            return mapper.readTree(body.readAllBytes());
        }
    }

    private static List<WebResult> collect(JsonNode arr, String t, String u, String s) {
        List<WebResult> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode n : arr) {
            String url = n.path(u).asString("");
            if (!(url.startsWith("https://") || url.startsWith("http://"))) continue;
            String snippet = n.path(s).asString("").replaceAll("<[^>]+>", "").strip();
            if (snippet.length() > MAX_SNIPPET) snippet = snippet.substring(0, MAX_SNIPPET);
            out.add(new WebResult(n.path(t).asString(url).strip(), url, snippet));
        }
        return out;
    }

    private static boolean isAllowed(Config c, String url) {
        if (c.blockedDomains().isEmpty()) return true;
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return c.blockedDomains().stream().noneMatch(d -> h.equals(d) || h.endsWith("." + d));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
