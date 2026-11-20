package com.keplura.server.websearch;

import com.keplura.server.admin.SecretCipher;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Websuche in der Administration (CHAT-02). Unter {@code /api/v1/admin/**}: Session + CSRF +
 * globale ADMIN-Rolle. Der API-Schlüssel ist write-only und wird mit {@link SecretCipher}
 * verschlüsselt gespeichert; Antworten melden nur {@code hasApiKey}.
 */
@RestController
@RequestMapping("/api/v1/admin/web-search")
public class WebSearchAdminController {

    public record View(String source, String provider, String url, boolean hasApiKey, int results,
                       String blockedDomains, boolean enabled, boolean secretStorageAvailable) {
    }

    /** apiKey: null = unverändert, "" = löschen. */
    public record Input(String provider, String url, String apiKey, Integer results, String blockedDomains) {
    }

    public record TestResult(boolean ok, String message, List<WebSearchService.WebResult> results) {
    }

    public record ErrorResponse(String error) {
    }

    private final WebSearchService service;
    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    public WebSearchAdminController(WebSearchService service, JdbcTemplate jdbc, SecretCipher cipher) {
        this.service = service;
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @GetMapping
    public View get() {
        WebSearchService.Config c = service.effective();
        return new View(c.source(), c.provider(), c.baseUrl(), !c.apiKey().isEmpty(), c.results(),
                String.join(", ", c.blockedDomains()), c.enabled(), cipher.available());
    }

    @PutMapping
    public ResponseEntity<?> put(@RequestBody Input in) {
        String provider = in.provider() == null ? "none" : in.provider().strip().toLowerCase(Locale.ROOT);
        if (!WebSearchService.PROVIDERS.contains(provider)) return bad("Unbekannter Anbieter.");
        String url = in.url() == null ? "" : in.url().strip();
        if (!url.isEmpty() && !(url.startsWith("https://") || url.startsWith("http://")) || url.length() > 500) {
            return bad("Die Adresse muss mit http:// oder https:// beginnen.");
        }
        if (!url.isEmpty()) {
            try {
                var target = com.keplura.server.admin.SsrfGuard.parseShape(url);
                if (target.getRawQuery() != null || target.getRawFragment() != null) {
                    return bad("Die Adresse darf keine Abfrageparameter oder Fragmente enthalten.");
                }
            } catch (com.keplura.server.admin.AdminException e) {
                return bad("Bitte eine gültige Serveradresse ohne eingebettete Zugangsdaten angeben.");
            }
        }
        if ("searxng".equals(provider) && url.isEmpty()) return bad("Für SearXNG wird die Adresse der Instanz benötigt.");
        String blocked = in.blockedDomains() == null ? "" : in.blockedDomains().strip();
        if (blocked.length() > 2000) return bad("Die Liste gesperrter Domains ist zu lang.");
        int results = in.results() == null ? 5 : Math.max(1, Math.min(in.results(), 10));

        List<String> existing = jdbc.query("select api_key_enc from web_search_settings where id = 1",
                (rs, n) -> rs.getString(1));
        String existingEnc = existing.isEmpty() ? null : existing.getFirst();
        String enc = existingEnc;
        if (in.apiKey() != null) {
            if (in.apiKey().isBlank()) {
                enc = null;
            } else {
                if (!cipher.available()) {
                    return bad("Schlüssel können nicht gespeichert werden: KEPLURA_SECRETS_MASTER_KEY ist nicht gesetzt.");
                }
                enc = cipher.encrypt(in.apiKey().strip());
            }
        }
        if (("brave".equals(provider) || "tavily".equals(provider)) && enc == null) {
            return bad("Für diesen Anbieter wird ein API-Schlüssel benötigt.");
        }
        Timestamp now = Timestamp.from(Instant.now());
        int n = jdbc.update("update web_search_settings set provider = ?, url = ?, api_key_enc = ?, results = ?, blocked_domains = ?, updated_at = ? where id = 1",
                provider, url, enc, results, blocked, now);
        if (n == 0) {
            jdbc.update("insert into web_search_settings (id, provider, url, api_key_enc, results, blocked_domains, updated_at) values (1, ?, ?, ?, ?, ?, ?)",
                    provider, url, enc, results, blocked, now);
        }
        return ResponseEntity.ok(get());
    }

    /** Probe-Suche mit der gespeicherten Einstellung. */
    @PostMapping("/test")
    public TestResult test() {
        WebSearchService.Config c = service.effective();
        if (!c.enabled()) return new TestResult(false, "Die Websuche ist nicht eingerichtet.", List.of());
        try {
            List<WebSearchService.WebResult> r = service.search(c, "Keplura");
            return new TestResult(true, r.size() + " Treffer gefunden.", r);
        } catch (WebSearchService.WebSearchException e) {
            return new TestResult(false, "Der Suchdienst ist nicht erreichbar oder hat abgelehnt.", List.of());
        }
    }

    private static ResponseEntity<ErrorResponse> bad(String m) {
        return ResponseEntity.badRequest().body(new ErrorResponse(m));
    }
}
