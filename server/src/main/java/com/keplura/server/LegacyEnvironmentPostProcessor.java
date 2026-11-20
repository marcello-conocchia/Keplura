package com.keplura.server;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Keeps installations from before the IntraCore → Keplura rename (ADR-0013)
 * working: every {@code INTRACORE_*} environment variable is exposed as its
 * {@code KEPLURA_*} counterpart when that one is absent; both names present
 * with different values aborts the start (see below). The copies sit directly BELOW the real system environment and are
 * named {@code ...-systemEnvironment}, so Spring Boot applies the same relaxed
 * binding ({@code KEPLURA_GATEWAY_BASE_URL} → {@code keplura.gateway.base-url})
 * and {@code ${KEPLURA_...}} placeholders resolve as before.
 *
 * <p>A legacy variable that is set (non-empty) while its new counterpart is
 * ALSO present with a different value is a conflict and aborts the start.
 * That is exactly the state of a Compose install whose .env.production was
 * not migrated: compose.prod.yml passes {@code KEPLURA_X=""}/defaults plus the
 * detector variables {@code INTRACORE_SECRETS_MASTER_KEY},
 * {@code INTRACORE_SERVER_HOST_PORT} and {@code INTRACORE_PROVIDER}; starting anyway would silently run
 * without the master key and with default provider settings.
 *
 * <p>Only names are logged, never values (secrets live in these variables).
 */
public class LegacyEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String LEGACY_PREFIX = "INTRACORE_";
    static final String PREFIX = "KEPLURA_";
    static final String SOURCE_NAME = "legacyIntracore-" + StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> systemEnv = environment.getSystemEnvironment();
        java.util.List<String> conflicts = conflicts(systemEnv);
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException("Keplura: alte und neue Umgebungsvariablen widersprechen sich "
                    + conflicts + " — .env.production wurde nicht auf KEPLURA_* umgestellt. "
                    + "Bitte einmal deploy/install.sh ausführen (ADR-0013).");
        }
        Map<String, Object> mapped = map(systemEnv);
        if (mapped.isEmpty()) {
            return;
        }
        SystemEnvironmentPropertySource source = new SystemEnvironmentPropertySource(SOURCE_NAME, mapped);
        var sources = environment.getPropertySources();
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source);
        } else {
            sources.addLast(source);
        }
        // Logging is not initialised yet this early; stderr reaches the same log.
        System.err.println("WARN Keplura: veraltete Umgebungsvariablen erkannt, bitte auf KEPLURA_* umbenennen: "
                + new TreeSet<>(mapped.keySet()).stream().map(k -> LEGACY_PREFIX + k.substring(PREFIX.length())).toList());
    }

    /** Non-empty legacy {@code INTRACORE_X} → {@code KEPLURA_X} for every X not present under the new name. */
    static Map<String, Object> map(Map<String, Object> env) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        env.forEach((key, value) -> {
            // empty legacy values are ignored (compose passes the detectors as "")
            if (key.startsWith(LEGACY_PREFIX) && value != null && !String.valueOf(value).isEmpty()) {
                String renamed = PREFIX + key.substring(LEGACY_PREFIX.length());
                if (!env.containsKey(renamed)) {
                    mapped.put(renamed, value);
                }
            }
        });
        return mapped;
    }

    /** Legacy names that are non-empty while the new name is present with a different value. */
    static java.util.List<String> conflicts(Map<String, Object> env) {
        return env.entrySet().stream()
                .filter(e -> e.getKey().startsWith(LEGACY_PREFIX))
                .filter(e -> e.getValue() != null && !String.valueOf(e.getValue()).isEmpty())
                .filter(e -> {
                    String renamed = PREFIX + e.getKey().substring(LEGACY_PREFIX.length());
                    return env.containsKey(renamed) && !String.valueOf(e.getValue()).equals(String.valueOf(env.get(renamed)));
                })
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
