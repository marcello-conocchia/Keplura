package com.keplura.server;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness endpoint for operators and deployment probes: answers as long as the
 * HTTP stack is serving. Deliberately separate from {@link SystemInfoController}
 * (/api/v1/system/info), which is the desktop-facing product surface — health is
 * operations-only and carries no product/version data. No CORS: the desktop
 * app never calls it (see docs/development.md, M1a).
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    /** Response payload of GET /api/v1/health. */
    public record Health(String status) {
    }

    @GetMapping
    public Health health() {
        return new Health("UP");
    }
}
