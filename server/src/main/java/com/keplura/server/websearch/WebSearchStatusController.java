package com.keplura.server.websearch;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ob die Websuche eingerichtet ist (für den „Web"-Schalter im Chat). Nur angemeldet (/orgs/**). */
@RestController
public class WebSearchStatusController {

    public record Status(boolean enabled, String provider) {
    }

    private final WebSearchService service;

    public WebSearchStatusController(WebSearchService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/orgs/{orgId}/web-search/status")
    public Status status() {
        return new Status(service.enabled(), service.providerName());
    }
}
