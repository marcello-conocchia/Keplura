package com.keplura.server.tool;

import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * TU-06d: decides the type of a tool result file from its BYTES (magic bytes), never from the declared type alone.
 * Allowlist: PNG, JPEG, WebP, GIF, PDF, CSV, plain text, JSON. SVG, HTML/XML and everything else are refused.
 * Images must state their dimensions in the header and stay at or below {@link #MAX_PIXELS}. Pure, never throws.
 */
public final class ArtifactInspector {

    public static final long MAX_PIXELS = 40_000_000L;
    public static final Set<String> IMAGES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");
    public static final Set<String> TEXTS = Set.of("text/csv", "text/plain", "application/json");
    public static final Set<String> ALLOWED;
    static {
        var all = new java.util.HashSet<>(IMAGES);
        all.addAll(TEXTS);
        all.add("application/pdf");
        ALLOWED = Set.copyOf(all);
    }
    static final Map<String, String> EXTENSIONS = Map.of("image/png", "png", "image/jpeg", "jpg", "image/webp", "webp",
            "image/gif", "gif", "application/pdf", "pdf", "text/csv", "csv", "text/plain", "txt", "application/json", "json");

    /** {@code type} = verified type or {@code null}; then {@code reason} is a fixed text for the card. */
    public record Verdict(String type, String reason) {
        static Verdict ok(String type) { return new Verdict(type, null); }
        static Verdict no(String reason) { return new Verdict(null, reason); }
    }

    private ArtifactInspector() { }

    public static String normalize(String declared) {
        if (declared == null) return "";
        String t = declared.toLowerCase(Locale.ROOT).strip();
        int semi = t.indexOf(';');
        return (semi < 0 ? t : t.substring(0, semi)).strip();
    }

    public static Verdict inspect(String declaredType, byte[] data) {
        try {
            if (data == null || data.length == 0) return Verdict.no("Die Datei ist leer.");
            String declared = normalize(declaredType);
            if (!declared.isEmpty() && !ALLOWED.contains(declared)) {
                return Verdict.no("Dieser Dateityp ist nicht erlaubt.");
            }
            String detected = binaryType(data);
            if (detected != null) {
                if (!declared.isEmpty() && !declared.equals(detected)) return Verdict.no("Der Dateityp passt nicht zum Inhalt.");
                if (IMAGES.contains(detected)) {
                    long[] size = dimensions(detected, data);
                    if (size == null || size[0] <= 0 || size[1] <= 0) return Verdict.no("Die Bildgröße ist nicht lesbar.");
                    if (size[0] * size[1] > MAX_PIXELS) return Verdict.no("Das Bild ist größer als 40 Megapixel.");
                }
                return Verdict.ok(detected);
            }
            // No binary signature: only one of the declared text types, and only if the bytes are clean text.
            if (!TEXTS.contains(declared)) return Verdict.no(declared.isEmpty()
                    ? "Der Dateityp ist nicht erkennbar." : "Der Dateityp passt nicht zum Inhalt.");
            String text = utf8(data);
            if (text == null) return Verdict.no("Die Textdatei ist kein gültiges UTF-8.");
            String body = text.startsWith("﻿") ? text.substring(1) : text;
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') return Verdict.no("Die Textdatei enthält Steuerzeichen.");
            }
            if (body.stripLeading().startsWith("<")) return Verdict.no("Markup wie HTML, SVG oder XML ist nicht erlaubt.");
            if (declared.equals("application/json")) {
                try {
                    JsonMapper.builder().build().readTree(body);
                } catch (RuntimeException e) {
                    return Verdict.no("Die JSON-Datei ist ungültig.");
                }
            }
            return Verdict.ok(declared);
        } catch (RuntimeException e) {
            return Verdict.no("Die Datei konnte nicht sicher geprüft werden.");
        }
    }

    /** Binary signature or {@code null}. */
    static String binaryType(byte[] d) {
        if (starts(d, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return "image/png";
        if (starts(d, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
        if (starts(d, 'G', 'I', 'F', '8', '7', 'a') || starts(d, 'G', 'I', 'F', '8', '9', 'a')) return "image/gif";
        if (d.length >= 16 && starts(d, 'R', 'I', 'F', 'F') && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return "image/webp";
        if (starts(d, '%', 'P', 'D', 'F', '-')) return "application/pdf";
        return null;
    }

    private static boolean starts(byte[] d, int... sig) {
        if (d.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) if ((d[i] & 0xff) != sig[i]) return false;
        return true;
    }

    private static int u8(byte[] d, int i) { return d[i] & 0xff; }
    private static int be16(byte[] d, int i) { return (u8(d, i) << 8) | u8(d, i + 1); }
    private static int le16(byte[] d, int i) { return u8(d, i) | (u8(d, i + 1) << 8); }
    private static long be32(byte[] d, int i) { return ((long) be16(d, i) << 16) | be16(d, i + 2); }
    private static int le24(byte[] d, int i) { return u8(d, i) | (u8(d, i + 1) << 8) | (u8(d, i + 2) << 16); }

    /** [width, height] from the header, or {@code null} when not readable. */
    static long[] dimensions(String type, byte[] d) {
        switch (type) {
            case "image/png" -> {
                if (d.length < 24 || d[12] != 'I' || d[13] != 'H' || d[14] != 'D' || d[15] != 'R') return null;
                return new long[]{be32(d, 16), be32(d, 20)};
            }
            case "image/gif" -> {
                if (d.length < 10) return null;
                return new long[]{le16(d, 6), le16(d, 8)};
            }
            case "image/webp" -> {
                if (d.length < 30) return null;
                String chunk = new String(d, 12, 4, StandardCharsets.US_ASCII);
                return switch (chunk) {
                    case "VP8 " -> d.length < 30 ? null : new long[]{le16(d, 26) & 0x3fff, le16(d, 28) & 0x3fff};
                    case "VP8L" -> {
                        if (d[20] != 0x2f) yield null;
                        long bits = u8(d, 21) | ((long) u8(d, 22) << 8) | ((long) u8(d, 23) << 16) | ((long) u8(d, 24) << 24);
                        yield new long[]{(bits & 0x3fff) + 1, ((bits >> 14) & 0x3fff) + 1};
                    }
                    case "VP8X" -> new long[]{le24(d, 24) + 1L, le24(d, 27) + 1L};
                    default -> null;
                };
            }
            case "image/jpeg" -> {
                int i = 2;
                while (i + 9 < d.length) {
                    if (u8(d, i) != 0xFF) return null;
                    int marker = u8(d, i + 1);
                    if (marker == 0xFF) { i++; continue; }
                    if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { i += 2; continue; }
                    int length = be16(d, i + 2);
                    if (length < 2) return null;
                    boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
                    if (sof) return new long[]{be16(d, i + 7), be16(d, i + 5)};
                    if (marker == 0xDA || marker == 0xD9) return null;
                    i += 2 + length;
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private static String utf8(byte[] data) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
