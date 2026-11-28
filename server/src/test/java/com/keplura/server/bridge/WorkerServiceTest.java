package com.keplura.server.bridge;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerServiceTest {
    private static final String KEY = "runner-fixture-key-0123456789-0123456789";
    private static final String IMAGE = "registry.example.test/probe@sha256:" + "0".repeat(64);
    private static final String ROOTLESS = "{\"host\":{\"cgroupVersion\":\"v2\",\"security\":{\"rootless\":true}}}";
    private static final String LIMITS = "268435456\n0\n64\n100000 100000\n16384\n";
    private static final String SLOT = "a".repeat(32);

    private static WorkerConfig config() {
        String worker = "{\"image\":\"" + IMAGE + "\",\"program\":\"/bin/tool\"}";
        return WorkerConfig.parse(BridgeWire.JSON.readTree("{\"instance\":\"fixture\",\"probeImage\":\"" + IMAGE
                + "\",\"maxWorkers\":2,\"maxPerServer\":1,\"servers\":{\"a\":" + worker + ",\"b\":" + worker + ",\"c\":" + worker + "}}"));
    }

    private static class RuntimeFixture implements PodmanRuntime.Control {
        final List<List<String>> commands = new ArrayList<>();
        String limits = LIMITS;
        String info = ROOTLESS;

        @Override public String run(List<String> args) {
            commands.add(List.copyOf(args));
            return switch (args.getFirst()) {
                case "info" -> info;
                case "run" -> limits;
                case "ps" -> "abcd1234abcd\n";
                case "inspect" -> args.get(2).equals("{{.Path}}") ? "/bin/tool" : "[]";
                default -> "";
            };
        }

        @Override public Process attach(List<String> args) {
            commands.add(List.copyOf(args));
            return null; // No actual process is spawned in this fixture.
        }
    }

    private static WorkerService service(RuntimeFixture fixture) {
        return new WorkerService(config(), KEY.getBytes(StandardCharsets.US_ASCII), new PodmanRuntime(fixture));
    }

    @Test void rejectsStartupWhenAnyEffectiveLimitIsMissingOrUnbounded() {
        for (String limits : List.of("", "max 0 64 100000 100000 16384", "268435456 max 64 100000 100000 16384",
                "268435456 0 max 100000 100000 16384", "268435456 0 64 max 100000 16384",
                "268435456 0 64 100000 100000 unlimited")) {
            var fixture = new RuntimeFixture();
            fixture.limits = limits;
            try (var service = service(fixture)) {
                assertThatThrownBy(service::prepare).isInstanceOf(IOException.class).hasMessage("ineffective container limits");
                assertThat(service.reserve("a", SLOT)).isFalse();
                assertThat(fixture.commands).extracting(List::getFirst).containsExactly("info", "run");
            }
        }
    }

    @Test void refusesARootfulRuntimeBeforeItCanRunEvenTheProbe() {
        var fixture = new RuntimeFixture();
        fixture.info = ROOTLESS.replace("true", "false");
        try (var service = service(fixture)) {
            assertThatThrownBy(service::prepare).isInstanceOf(IOException.class);
            assertThat(service.reserve("a", SLOT)).isFalse();
            assertThat(fixture.commands).extracting(List::getFirst).containsExactly("info");
        }
    }

    @Test void reapsOnlyItsOwnLabelsBeforeAdmittingPerServerAndGlobalSlots() throws IOException {
        var fixture = new RuntimeFixture();
        try (var service = service(fixture)) {
            service.prepare();
            assertThat(fixture.commands).contains(List.of("ps", "--all", "--filter",
                    "label=com.keplura.stdio.instance=fixture", "--format", "{{.ID}}"),
                    List.of("rm", "--force", "--ignore", "abcd1234abcd"));
            assertThat(service.reserve("a", SLOT)).isTrue();
            String second = "b".repeat(32);
            assertThat(service.reserve("a", second)).isFalse();
            assertThat(service.reserve("b", second)).isTrue();
            assertThat(service.reserve("c", "c".repeat(32))).isFalse();
            service.release(SLOT);
            assertThat(service.reserve("c", "c".repeat(32))).isTrue();
        }
    }

    @Test void startingAWorkerAcceptsOnlyDeclaredServerAndIndependentKey() throws IOException {
        var fixture = new RuntimeFixture();
        try (var service = service(fixture)) {
            String request = "{\"server\":\"a\",\"session\":\"" + SLOT + "\",\"key\":\"" + KEY + "\"}";
            assertThat(service.authenticate(bytes(request))).isEqualTo(new WorkerService.Start("a", SLOT));
            for (String name : List.of("image", "mount", "network", "privileged", "argv", "environment")) {
                assertThatThrownBy(() -> service.authenticate(bytes(request.substring(0, request.length() - 1)
                        + ",\"" + name + "\":\"forbidden\"}"))).isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> service.authenticate(bytes(request.replace(KEY, "bridge-key-fixture")))).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> service.authenticate(bytes(request.replace("\"a\"", "\"unknown\"")))).isInstanceOf(IOException.class);
            assertThat(fixture.commands).isEmpty();
        }
        assertThatThrownBy(() -> WorkerConfig.parse(BridgeWire.JSON.readTree("{\"network\":\"forbidden\"}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void startUsesHardenedFixedArgvAndDigest() throws IOException {
        var fixture = new RuntimeFixture();
        var runtime = new PodmanRuntime(fixture);
        runtime.start("fixture", SLOT, config().servers().get("a"));
        var create = fixture.commands.getFirst();
        assertThat(create).contains("--network=none", "--pull=never", "--read-only", "--user=65534:65534", "--cap-drop=ALL",
                "--security-opt=no-new-privileges", "--pids-limit=64", "--entrypoint=/bin/tool", IMAGE);
        assertThat(create).noneMatch(arg -> arg.startsWith("--mount") || arg.startsWith("--volume") || arg.startsWith("--privileged"));
        assertThat(create.getLast()).isEqualTo(IMAGE);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void workerProfileRetainsTheBaselineAndDisallowsNetworkHandles(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
            throws IOException {
        var baseline = directory.resolve("baseline.json");
        java.nio.file.Files.writeString(baseline, """
                {"defaultAction":"SCMP_ACT_ERRNO","syscalls":[{"names":["read","socket","io_uring_setup"],"action":"SCMP_ACT_ALLOW"},
                 {"names":["socketcall"],"action":"SCMP_ACT_ALLOW"}]}
                """);
        var profile = PodmanRuntime.writeWorkerProfile(baseline, directory);
        var result = BridgeWire.JSON.readTree(java.nio.file.Files.readAllBytes(profile));
        assertThat(result.path("syscalls").size()).isEqualTo(1);
        assertThat(result.path("syscalls").get(0).path("names").toString()).isEqualTo("[\"read\"]");
        java.nio.file.Files.writeString(baseline, "{\"defaultAction\":\"SCMP_ACT_ALLOW\",\"syscalls\":[]}");
        assertThatThrownBy(() -> PodmanRuntime.writeWorkerProfile(baseline, directory)).isInstanceOf(IOException.class);
    }

    @Test void outputMustBeBoundedAndAValidResponse() throws IOException {
        String reply = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}";
        assertThat(WorkerService.response(new ByteArrayInputStream(bytes(reply + "\n")))).isEqualTo(bytes(reply));
        assertThatThrownBy(() -> WorkerService.response(new ByteArrayInputStream(bytes("invalid\n")))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> WorkerService.response(new ByteArrayInputStream(bytes("a".repeat(BridgeWire.MAX_REPLY + 1)))))
                .isInstanceOf(IOException.class);
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
