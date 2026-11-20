package com.keplura.server;

import com.keplura.server.gateway.GatewayRuntime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only system information endpoint. Contract lives in
 * contracts/openapi.yaml (GET /api/v1/system/info).
 *
 * <p>M4c adds the {@code model} state — the ONLY provider fact allowed out
 * here, from a closed three-value vocabulary: {@code none} (no model wired),
 * {@code local} (Ollama preset — the PO's privacy posture shows on the UI)
 * or {@code configured} (a remote backend is dialed; vLLM or hosted API).
 * Deliberately never an API key, a base URL, a raw provider string or an
 * upstream model id: this endpoint is unauthenticated (desktop handoff
 * screen), so anything richer than an enum is a leak by contract.
 *
 * <p>{@code @CrossOrigin} is required because the Keplura Desktop webview runs
 * on its own origin (Vite dev server / tauri://localhost) and calls this API
 * over plain HTTPS/HTTP.
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/v1/system")
public class SystemInfoController {

    private static final Logger log = LoggerFactory.getLogger(SystemInfoController.class);

    /** Response payload of GET /api/v1/system/info. */
    public record SystemInfo(String product, String serverVersion, String apiVersion, String model) {
    }

    private final String product;
    private final String serverVersion;
    private final String apiVersion;
    private final GatewayRuntime runtime;

    public SystemInfoController(
            @Value("${keplura.product}") String product,
            @Value("${keplura.server-version}") String serverVersion,
            @Value("${keplura.api-version}") String apiVersion,
            GatewayRuntime runtime) {
        this.product = product;
        this.serverVersion = serverVersion;
        this.apiVersion = apiVersion;
        this.runtime = runtime;
        // Boot-time health echo (M4c): the operator sees the resolved ENV state
        // without any endpoint required — id and booleans only, never URL/key.
        // ADMIN-KI: DB rows may override per request later; the ENV boot
        // state is what this line honestly reports.
        var env = runtime.envProperties();
        log.info("model provider state: provider={} configured={} keyPresent={}",
                env.provider().isEmpty() ? "raw" : env.provider(),
                env.configured(),
                !env.apiKey().isEmpty());
    }

    @GetMapping("/info")
    public SystemInfo info() {
        return new SystemInfo(product, serverVersion, apiVersion, modelState());
    }

    /** Closed-vocabulary projection of the EFFECTIVE provider config (never raw values). */
    private String modelState() {
        // ADMIN-KI: DB provider/model rows now win over ENV, so the public
        // state must reflect the effective config, not just the boot bean.
        // GatewayRuntime.modelState() applies the same none|local|configured
        // vocabulary with DB precedence (docs/adr/0008 §4 leak discipline).
        return runtime.modelState();
    }
}
