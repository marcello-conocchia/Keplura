package com.keplura.server.bridge;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * TU-06c: the operator's declaration of stdio MCP servers, read ONLY from a deployment file. Every command is an
 * argument list with an absolute program path (never a shell), fixed environment variables (no Keplura secrets) and
 * a working directory. Unknown keys or values outside the limits refuse the whole file (fail closed).
 */
public final class BridgeConfig {

    public record ServerSpec(String name, List<String> command, Map<String, String> env, Path workdir, Duration timeout,
                             int maxOutputBytes, int maxSessions, Duration idleTimeout) { }

    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    static final Pattern ENV_KEY = Pattern.compile("[A-Z_][A-Z0-9_]{0,63}");
    /** Shells and launchers that would turn a declaration back into a command line. */
    static final Set<String> SHELLS = Set.of("sh", "bash", "dash", "zsh", "ksh", "ash", "csh", "tcsh", "fish", "busybox",
            "env", "su", "sudo", "doas", "cmd", "cmd.exe", "powershell", "pwsh");
    private static final Set<String> SERVER_KEYS = Set.of("name", "command", "env", "workdir", "timeoutSeconds",
            "maxOutputBytes", "maxSessions", "idleSeconds");

    public static class InvalidConfig extends RuntimeException {
        public InvalidConfig(String message) { super(message); }
    }

    private BridgeConfig() { }

    public static List<ServerSpec> parse(byte[] raw) {
        JsonNode root;
        try {
            root = JsonMapper.builder().build().readTree(raw);
        } catch (RuntimeException e) {
            throw new InvalidConfig("config is not valid JSON");
        }
        if (root == null || !root.isObject() || root.size() != 1 || !root.path("servers").isArray()) {
            throw new InvalidConfig("config must be an object with exactly one key \"servers\" (a list)");
        }
        List<ServerSpec> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JsonNode s : root.path("servers")) {
            if (!s.isObject()) throw new InvalidConfig("every server must be an object");
            for (String key : s.propertyNames()) {
                if (!SERVER_KEYS.contains(key)) throw new InvalidConfig("unknown key in server declaration: " + key);
            }
            String name = s.path("name").asString("");
            if (!NAME.matcher(name).matches() || !names.add(name)) throw new InvalidConfig("server names must be unique [a-z0-9-]{1,40}");
            JsonNode cmd = s.path("command");
            if (!cmd.isArray() || cmd.isEmpty() || cmd.size() > 64) throw new InvalidConfig(name + ": command must be a non-empty argument list");
            List<String> command = new ArrayList<>();
            for (JsonNode part : cmd) {
                if (!part.isString() || part.asString().length() > 4096 || part.asString().indexOf('\0') >= 0) {
                    throw new InvalidConfig(name + ": every argument must be a string");
                }
                command.add(part.asString());
            }
            Path program = Path.of(command.getFirst());
            if (!program.isAbsolute() || command.getFirst().contains("..")) throw new InvalidConfig(name + ": the program needs an absolute path");
            if (SHELLS.contains(program.getFileName().toString().toLowerCase(java.util.Locale.ROOT))) {
                throw new InvalidConfig(name + ": shells and launchers are not allowed as program");
            }
            Map<String, String> env = new LinkedHashMap<>();
            JsonNode e = s.path("env");
            if (!e.isMissingNode()) {
                if (!e.isObject() || e.size() > 64) throw new InvalidConfig(name + ": env must be an object");
                for (var entry : e.properties()) {
                    String key = entry.getKey();
                    if (!ENV_KEY.matcher(key).matches() || key.startsWith("KEPLURA_") || key.startsWith("INTRACORE_")
                            || key.startsWith("SPRING_") || key.equals("LD_PRELOAD") || key.equals("LD_LIBRARY_PATH")
                            || key.equals("JAVA_TOOL_OPTIONS") || key.equals("_JAVA_OPTIONS")) {
                        throw new InvalidConfig(name + ": environment variable not allowed: " + key);
                    }
                    if (!entry.getValue().isString() || entry.getValue().asString().length() > 4096
                            || entry.getValue().asString().indexOf('\0') >= 0) {
                        throw new InvalidConfig(name + ": environment values must be strings");
                    }
                    env.put(key, entry.getValue().asString());
                }
            }
            String dir = s.path("workdir").asString("");
            if (dir.isEmpty() || !Path.of(dir).isAbsolute() || dir.contains("..")) throw new InvalidConfig(name + ": workdir must be an absolute path");
            int timeout = intIn(s, "timeoutSeconds", 30, 1, 120, name);
            int maxOutput = intIn(s, "maxOutputBytes", 4_000_000, 65_536, 16_000_000, name);
            int sessions = intIn(s, "maxSessions", 4, 1, 16, name);
            int idle = intIn(s, "idleSeconds", 60, 5, 600, name);
            out.add(new ServerSpec(name, List.copyOf(command), Map.copyOf(env), Path.of(dir), Duration.ofSeconds(timeout),
                    maxOutput, sessions, Duration.ofSeconds(idle)));
        }
        if (out.isEmpty() || out.size() > 32) throw new InvalidConfig("declare between 1 and 32 servers");
        return List.copyOf(out);
    }

    private static int intIn(JsonNode s, String key, int dflt, int min, int max, String name) {
        if (!s.has(key)) return dflt;
        JsonNode v = s.get(key);
        if (!v.isIntegralNumber() || !v.canConvertToInt() || v.asInt() < min || v.asInt() > max) {
            throw new InvalidConfig(name + ": " + key + " must be between " + min + " and " + max);
        }
        return v.asInt();
    }
}
