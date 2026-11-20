package com.keplura.server.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * ADMIN-KI Scope E + D surfaces: the passive system status board, the
 * read-only RAG settings view, and the explicit .env seed offer
 * (ADR-0008 §5.3 — a click, not a silent migration).
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminSystemController {

    private final AdminSystemStatusService statusService;
    private final AdminSeedService seedService;

    public AdminSystemController(AdminSystemStatusService statusService,
                                 AdminSeedService seedService) {
        this.statusService = statusService;
        this.seedService = seedService;
    }

    @GetMapping("/system-status")
    public Map<String, AdminSystemStatusService.ComponentState> systemStatus() {
        return statusService.status();
    }

    @GetMapping("/rag-settings")
    public Map<String, Object> ragSettings() {
        return statusService.ragSettings();
    }

    @GetMapping("/seed-offer")
    public AdminSeedService.SeedOffer seedOffer() {
        return seedService.offer();
    }

    @PostMapping("/seed-from-env")
    public AdminSeedService.SeedResult seedFromEnv() {
        return seedService.seedFromEnv();
    }
}
