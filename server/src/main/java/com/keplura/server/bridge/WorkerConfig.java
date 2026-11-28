package com.keplura.server.bridge;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

record WorkerConfig(String instance, String probeImage, int maxWorkers, int maxPerServer, Map<String, Worker> servers) {
    static final int MEMORY_MIB = 256;
    static final int PIDS = 64;
    static final int FILE_BYTES = 8 * 1024 * 1024;
    static final int TIMEOUT_SECONDS = 60;

    record Worker(String image, String program, List<String> arguments, Map<String,String> environment) { }

    static WorkerConfig read(Path file) throws IOException {
        if (Files.size(file) > 65_536) throw new IOException("invalid configuration");
        return parse(BridgeWire.JSON.readTree(Files.readAllBytes(file)));
    }

    static WorkerConfig parse(JsonNode root) {
        fields(root, Set.of("instance", "probeImage", "maxWorkers", "maxPerServer", "servers"));
        String instance = root.path("instance").asString("");
        if (!BridgeWire.name(instance)) throw new IllegalArgumentException("invalid instance");
        int global = number(root, "maxWorkers", 8);
        int perServer = number(root, "maxPerServer", 2);
        if (global > 32 || perServer > global) throw new IllegalArgumentException("invalid concurrency limit");
        if (!root.path("servers").isObject() || root.path("servers").size() > 32 || root.path("servers").isEmpty()) {
            throw new IllegalArgumentException("invalid servers");
        }
        Map<String, Worker> servers = new LinkedHashMap<>();
        for (var entry : root.path("servers").properties()) {
            String name = entry.getKey();
            JsonNode node = entry.getValue();
            fields(node, Set.of("image", "program", "arguments", "environment"));
            String program = node.path("program").asString("");
            if (!BridgeWire.name(name) || !program.matches("/[A-Za-z0-9_./-]{1,255}")
                    || Set.of("sh", "bash", "dash", "ash", "zsh", "ksh", "fish", "busybox", "env").contains(program.substring(program.lastIndexOf('/') + 1))) {
                throw new IllegalArgumentException("invalid worker");
            }
            List<String> args = new ArrayList<>();
            if (node.has("arguments") && !node.path("arguments").isArray() || node.path("arguments").size() > 32) {
                throw new IllegalArgumentException("invalid arguments");
            }
            for (JsonNode value : node.path("arguments")) args.add(value(value));
            Map<String,String> environment = new LinkedHashMap<>();
            if (node.has("environment") && !node.path("environment").isObject() || node.path("environment").size() > 16) {
                throw new IllegalArgumentException("invalid environment");
            }
            for (var env : node.path("environment").properties()) {
                if (!env.getKey().matches("[A-Z][A-Z0-9_]{0,39}")
                        || env.getKey().startsWith("KEPLURA_") || env.getKey().startsWith("LD_")
                        || Set.of("PATH", "HOME", "LANG", "TMPDIR", "BASH_ENV", "ENV").contains(env.getKey())) {
                    throw new IllegalArgumentException("invalid environment");
                }
                environment.put(env.getKey(), value(env.getValue()));
            }
            servers.put(name, new Worker(image(node.path("image").asString("")), program, List.copyOf(args), Map.copyOf(environment)));
        }
        return new WorkerConfig(instance, image(root.path("probeImage").asString("")), global, perServer, Map.copyOf(servers));
    }

    private static String value(JsonNode node) {
        String value = node.asString("");
        if (!node.isString() || value.length() > 1024 || value.indexOf(0) >= 0) throw new IllegalArgumentException("invalid value");
        return value;
    }

    private static String image(String value) {
        if (!value.matches("[a-z0-9][a-z0-9.:/_-]{1,220}@sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("image digest required");
        return value;
    }

    private static int number(JsonNode root, String key, int fallback) {
        if (!root.has(key)) return fallback;
        var value = root.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1) throw new IllegalArgumentException("invalid limit");
        return value.asInt();
    }

    static void fields(JsonNode node, Set<String> expected) {
        if (!node.isObject() || node.properties().stream().anyMatch(entry -> !expected.contains(entry.getKey()))) {
            throw new IllegalArgumentException("unknown field");
        }
    }
}
