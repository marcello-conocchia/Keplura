package com.keplura.server.bridge;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

final class PodmanRuntime {
    static final String EXECUTABLE = "/usr/bin/podman";
    private static final String LABEL = "com.keplura.stdio.instance";
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(10);

    // Fixed command in a separate operator-pinned probe image. Values are read inside the effective namespaces.
    private static final String PROBE = """
            set -eu
            cat /sys/fs/cgroup/memory.max /sys/fs/cgroup/memory.swap.max /sys/fs/cgroup/pids.max /sys/fs/cgroup/cpu.max
            ulimit -f
            """;

    interface Control {
        String run(List<String> args) throws IOException;
        Process attach(List<String> args) throws IOException;
    }

    private final Control control;
    private final Path profile;

    static Path writeWorkerProfile(Path baseline, Path directory) throws IOException {
        if (Files.size(baseline) > 262_144) throw new IOException("invalid seccomp baseline");
        var source = BridgeWire.JSON.readTree(Files.readAllBytes(baseline));
        if (!(source instanceof tools.jackson.databind.node.ObjectNode policy)
                || !"SCMP_ACT_ERRNO".equals(policy.path("defaultAction").asString(""))
                || !policy.path("syscalls").isArray()) throw new IOException("invalid seccomp baseline");
        var rules = BridgeWire.JSON.createArrayNode();
        var denied = Set.of("socket", "socketcall", "io_uring_setup", "io_uring_enter", "io_uring_register");
        for (var rule : policy.path("syscalls")) {
            if (!(rule instanceof tools.jackson.databind.node.ObjectNode object) || !rule.path("names").isArray()) {
                throw new IOException("invalid seccomp rule");
            }
            var names = BridgeWire.JSON.createArrayNode();
            for (var name : rule.path("names")) {
                if (!name.isString()) throw new IOException("invalid syscall name");
                if (!denied.contains(name.asString())) names.add(name.asString());
            }
            if (!names.isEmpty()) rules.add(object.deepCopy().set("names", names));
        }
        policy.set("syscalls", rules);
        Path path = Files.createTempFile(directory, "worker-seccomp-", ".json",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.write(path, BridgeWire.JSON.writeValueAsBytes(policy));
            return path;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(path);
            throw e;
        }
    }

    PodmanRuntime(Path profile) {
        this(new Control() {
            @Override public String run(List<String> args) throws IOException { return command(args); }
            @Override public Process attach(List<String> args) throws IOException { return process(args); }
        }, profile);
    }

    PodmanRuntime(Control control) { this(control, null); }

    private PodmanRuntime(Control control, Path profile) {
        this.control = control;
        this.profile = profile;
    }

    void verify(WorkerConfig config) throws IOException {
        var info = BridgeWire.JSON.readTree(control.run(List.of("info", "--format", "json")));
        var host = info.path("host");
        if (!host.path("security").path("rootless").asBoolean(false) || !"v2".equals(host.path("cgroupVersion").asString(""))) {
            throw new IOException("rootless cgroup v2 required");
        }
        var args = new ArrayList<>(List.of("run", "--rm"));
        args.addAll(hardening());
        if (profile != null) args.add("--security-opt=seccomp=" + profile);
        args.add("--entrypoint=/bin/sh");
        args.add(config.probeImage());
        args.addAll(List.of("-c", PROBE));
        String expectedMemory = Integer.toString(WorkerConfig.MEMORY_MIB * 1024 * 1024);
        if (!List.of(control.run(args).strip().split("\\s+")).equals(
                List.of(expectedMemory, "0", Integer.toString(WorkerConfig.PIDS), "100000", "100000",
                        Integer.toString(WorkerConfig.FILE_BYTES / 512)))) {
            throw new IOException("ineffective container limits");
        }
    }

    void reap(String instance) throws IOException {
        String ids = control.run(List.of("ps", "--all", "--filter", "label=" + LABEL + "=" + instance, "--format", "{{.ID}}")).strip();
        if (ids.isEmpty()) return;
        for (String id : ids.split("\\R")) {
            if (!id.matches("[a-f0-9]{12,64}")) throw new IOException("invalid runtime result");
            remove(id);
        }
    }

    Process start(String instance, String session, WorkerConfig.Worker config) throws IOException {
        String name = containerName(instance, session);
        var args = new ArrayList<>(List.of("create", "--interactive", "--name=" + name,
                "--label=" + LABEL + "=" + instance, "--label=com.keplura.stdio.session=" + session));
        args.addAll(hardening());
        if (profile != null) args.add("--security-opt=seccomp=" + profile);
        args.add("--entrypoint=" + config.program());
        config.environment().forEach((key, value) -> args.add("--env=" + key + "=" + value));
        args.add(config.image());
        args.addAll(config.arguments());
        control.run(args);
        String actualProgram = control.run(List.of("inspect", "--format", "{{.Path}}", name)).strip();
        var mounts = BridgeWire.JSON.readTree(control.run(List.of("inspect", "--format", "{{json .Mounts}}", name)));
        if (!mounts.isArray() || java.util.stream.StreamSupport.stream(mounts.spliterator(), false).anyMatch(mount -> !"tmpfs".equals(mount.path("Type").asString(""))
                || !"/work".equals(mount.path("Destination").asString("")))) throw new IOException("unexpected worker mounts");
        var actualArgs = BridgeWire.JSON.readTree(control.run(List.of("inspect", "--format", "{{json .Args}}", name)));
        List<String> actual = new ArrayList<>();
        actualArgs.forEach(node -> actual.add(node.asString("")));
        if (!actualProgram.equals(config.program()) || !actual.equals(config.arguments())) throw new IOException("unexpected worker argv");
        return control.attach(List.of("start", "--attach", "--interactive", name));
    }

    void stop(String instance, String session) throws IOException { remove(containerName(instance, session)); }

    private void remove(String name) throws IOException {
        control.run(List.of("rm", "--force", "--ignore", name));
    }

    private static String containerName(String instance, String session) { return "keplura-" + instance + "-" + session; }

    static List<String> hardening() {
        String memory = WorkerConfig.MEMORY_MIB + "m";
        return List.of("--pull=never", "--network=none", "--no-hosts", "--image-volume=ignore", "--http-proxy=false",
                "--pid=private", "--ipc=none", "--cgroupns=private", "--user=65534:65534", "--cap-drop=ALL",
                "--security-opt=no-new-privileges", "--read-only", "--read-only-tmpfs=false",
                "--tmpfs=/work:rw,nodev,nosuid,noexec,size=8m,mode=1777", "--workdir=/work",
                "--unsetenv-all", "--env=PATH=/usr/local/bin:/usr/bin:/bin", "--env=LANG=C.UTF-8",
                "--env=HOME=/work", "--env=TMPDIR=/work", "--log-driver=none", "--pids-limit=" + WorkerConfig.PIDS,
                "--memory=" + memory, "--memory-swap=" + memory, "--cpu-period=100000", "--cpu-quota=100000",
                "--ulimit=fsize=" + WorkerConfig.FILE_BYTES + ":" + WorkerConfig.FILE_BYTES,
                "--timeout=" + WorkerConfig.TIMEOUT_SECONDS);
    }

    private static Process process(List<String> args) throws IOException {
        var command = new ArrayList<>(List.of(EXECUTABLE, "--remote=false"));
        command.addAll(args);
        var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        Map<String, String> original = Map.copyOf(builder.environment());
        builder.environment().clear();
        for (String key : List.of("HOME", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS")) {
            if (original.containsKey(key)) builder.environment().put(key, original.get(key));
        }
        builder.environment().put("PATH", "/usr/bin:/bin");
        builder.environment().put("LANG", "C.UTF-8");
        return builder.start();
    }

    private static String command(List<String> args) throws IOException {
        Process process = process(args);
        FutureTask<byte[]> stdout = new FutureTask<>(() -> {
            try (var input = new BufferedInputStream(process.getInputStream())) {
                return input.readNBytes(65_537);
            }
        });
        Thread.ofVirtual().start(stdout);
        try {
            process.getOutputStream().close();
            if (!process.waitFor(CONTROL_TIMEOUT.toSeconds(), TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("runtime operation failed");
            }
            byte[] bytes = stdout.get(1, TimeUnit.SECONDS);
            if (bytes.length > 65_536) throw new IOException("invalid runtime response");
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("runtime operation interrupted");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IOException("runtime response unavailable");
        } finally {
            process.destroyForcibly();
            stdout.cancel(true);
        }
    }
}
