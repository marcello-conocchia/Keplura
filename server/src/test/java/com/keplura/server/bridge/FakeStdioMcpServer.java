package com.keplura.server.bridge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiny declared stdio MCP test process for the bridge (JDK only, no dependencies). Modes: {@code echo} (normal),
 * {@code hang} (never answers tools/call), {@code big} (answers tools/call with a huge line), {@code crash}
 * (exits on tools/call). The {@code env} tool answers with the names of its environment variables and cwd.
 */
public final class FakeStdioMcpServer {
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern TEXT = Pattern.compile("\"text\"\\s*:\\s*\"([^\"]*)\"");

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "echo";
        var out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        System.err.println("stderr noise that must never be forwarded");
        out.println("this is not json and must be ignored");
        var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = in.readLine()) != null; ) {
            Matcher id = ID.matcher(line);
            if (!id.find()) continue; // notification
            String rid = id.group(1);
            if (line.contains("\"initialize\"")) {
                out.println("{\"jsonrpc\":\"2.0\",\"id\":" + rid + ",\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fake\",\"version\":\"1\"}}}");
            } else if (line.contains("\"tools/list\"")) {
                out.println("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{}}");
                out.println("{\"jsonrpc\":\"2.0\",\"id\":" + rid + ",\"result\":{\"tools\":[{\"name\":\"echo\",\"description\":\"Echo\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}},{\"name\":\"env\",\"inputSchema\":{\"type\":\"object\"}}]}}");
            } else if (line.contains("\"tools/call\"")) {
                switch (mode) {
                    case "hang" -> Thread.sleep(600_000);
                    case "crash" -> System.exit(3);
                    case "big" -> out.println("{\"jsonrpc\":\"2.0\",\"id\":" + rid + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"" + "x".repeat(300_000) + "\"}]}}");
                    default -> {
                        String text;
                        if (line.contains("\"name\":\"env\"")) {
                            text = String.join(",", new TreeSet<>(System.getenv().keySet())) + ";cwd=" + System.getProperty("user.dir");
                        } else {
                            Matcher t = TEXT.matcher(line);
                            text = "echo:" + (t.find() ? t.group(1) : "");
                        }
                        out.println("{\"jsonrpc\":\"2.0\",\"id\":" + rid + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"" + text.replace("\\", "/").replace("\"", "'") + "\"}]}}");
                    }
                }
            }
        }
    }
}
