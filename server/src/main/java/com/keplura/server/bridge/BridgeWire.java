package com.keplura.server.bridge;

import tools.jackson.databind.json.JsonMapper;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

final class BridgeWire {
    static final JsonMapper JSON = JsonMapper.builder(tools.jackson.core.json.JsonFactory.builder()
                    .streamReadConstraints(tools.jackson.core.StreamReadConstraints.builder()
                            .maxNestingDepth(64).maxTokenCount(65_536).maxNameLength(1024).build()).build())
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    static final int MAX_REQUEST = 1_000_000;
    static final int MAX_REPLY = 16_000_000;
    static final int START = 1, REQUEST = 2, NOTIFY = 3, OK = 4, FAILED = 5, BUSY = 6;
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private BridgeWire() { }

    record Frame(int type, byte[] bytes) { }

    static Frame read(DataInputStream in, int limit) throws IOException {
        int type = in.readUnsignedByte();
        int size = in.readInt();
        if (size < 0 || size > limit) throw new IOException("invalid frame");
        byte[] bytes = in.readNBytes(size);
        if (bytes.length != size) throw new EOFException();
        return new Frame(type, bytes);
    }

    static void write(DataOutputStream out, int type, byte[] body) throws IOException {
        out.writeByte(type);
        out.writeInt(body.length);
        out.write(body);
        out.flush();
    }

    static byte[] secret(Path file) throws IOException {
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 513) {
            throw new IOException("invalid credential file");
        }
        var prohibited = Set.of(PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
                PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OWNER_EXECUTE);
        if (Files.getPosixFilePermissions(file).stream().anyMatch(prohibited::contains)) throw new IOException("invalid credential permissions");
        String value = Files.readString(file).strip();
        if (!value.matches("[!-~]{32,512}")) throw new IOException("invalid credential");
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    static boolean matches(byte[] secret, String received) {
        return received != null && MessageDigest.isEqual(secret, received.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] hash(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String id() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static boolean name(String value) { return value != null && value.matches("[a-z][a-z0-9-]{0,39}"); }
    static boolean session(String value) { return value != null && value.matches("[a-f0-9]{32}"); }

    static boolean nonRoot() throws IOException {
        try (var lines = Files.lines(Path.of("/proc/self/status"))) {
            return lines.filter(s -> s.startsWith("Uid:")).findFirst()
                    .map(s -> s.substring(4).strip().split("\\s+"))
                    .filter(ids -> ids.length == 4 && java.util.Arrays.stream(ids).noneMatch("0"::equals)).isPresent();
        }
    }
}
