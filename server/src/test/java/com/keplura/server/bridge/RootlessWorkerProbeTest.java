package com.keplura.server.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in probe against the local rootless runtime and a preinstalled, operator-pinned BusyBox image. */
@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "KEPLURA_TEST_STDIO_PROBE_IMAGE", matches = ".+@sha256:[0-9a-f]{64}")
class RootlessWorkerProbeTest {
    private static final String CHECK = """
            set -eu
            test "$(id -u)" = 65534
            grep '^NoNewPrivs:[[:space:]]*1$' /proc/self/status >/dev/null
            grep '^CapEff:[[:space:]]*0000000000000000$' /proc/self/status >/dev/null
            test "$(ls /sys/class/net)" = lo
            test ! -e /run/keplura-secrets && test ! -e /run/keplura-stdio
            test ! -e /var/run/docker.sock
            test ! -w / && test -w /work
            test -z "${KEPLURA_STDIO_RUNNER_KEY_FILE:-}" && test -z "${KEPLURA_STDIO_TOKEN_FILE:-}"
            test ! -e /work/marker
            : > /work/marker
            printf 'isolated\n'
            """;

    @Test void bootProbeAndTwoFreshContainersEnforceTheWorkerBoundaries(@TempDir Path directory) throws Exception {
        assertThat(BridgeWire.nonRoot()).isTrue();
        String image = System.getenv("KEPLURA_TEST_STDIO_PROBE_IMAGE");
        Path baseline = Path.of(System.getenv().getOrDefault("KEPLURA_STDIO_SECCOMP_BASELINE", "/usr/share/containers/seccomp.json"));
        Path profile = PodmanRuntime.writeWorkerProfile(baseline, directory);
        var runtime = new PodmanRuntime(profile);
        String instance = "probe-" + BridgeWire.id();
        // A fixed, trusted diagnostic. The service's declaration parser disallows shells in worker definitions.
        var diagnostic = new WorkerConfig.Worker(image, "/bin/sh", List.of("-c", CHECK), Map.of());
        try {
            runtime.verify(new WorkerConfig(instance, image, 2, 2, Map.of()));
            runProbe(runtime, instance, diagnostic);
            runProbe(runtime, instance, diagnostic);
        } finally {
            runtime.reap(instance);
            Files.deleteIfExists(profile);
        }
    }

    private static void runProbe(PodmanRuntime runtime, String instance, WorkerConfig.Worker diagnostic) throws Exception {
        String session = BridgeWire.id();
        Process process = null;
        try {
            process = runtime.start(instance, session, diagnostic);
            process.getOutputStream().close();
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            assertThat(new String(process.getInputStream().readNBytes(256), StandardCharsets.UTF_8)).isEqualTo("isolated\n");
        } finally {
            if (process != null) process.destroyForcibly();
            runtime.stop(instance, session);
        }
    }
}
