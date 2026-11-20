package com.keplura.server.datasource;

import com.keplura.server.access.AccessFilter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Protocol bridge: publish neither bytes nor ACL until the FD-bound helper exits successfully. */
final class NativeLocalReader {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "local-reader-timeout"); t.setDaemon(true); return t;
    });
    record Snapshot(Path file, SourceConnector.FileAcl acl, long size, Instant modifiedAt, String sha256)
            implements AutoCloseable {
        InputStream open() throws IOException { return Files.newInputStream(file); }
        @Override public void close() throws IOException { if (file != null) Files.deleteIfExists(file); }
    }
    private record Mode(int bits, long uid, long gid) { }
    private NativeLocalReader() { }

    /** Admin-verständliche Gründe zu den Exit-Codes des Helfers (siehe main.rs, enum Reason). */
    static final String UNSUPPORTED_FILESYSTEM = "Diese Datei liegt auf einem Netz- oder Sonderdateisystem "
            + "(z. B. NFS, CIFS, ZFS). Dort kann Keplura die Rechte noch nicht sicher übernehmen; "
            + "die Datei bleibt für alle gesperrt.";
    static final String EXTENDED_ACL = "Die Datei hat erweiterte Linux-Rechte (ACL). Diese wertet Keplura noch "
            + "nicht aus; die Datei bleibt für alle gesperrt.";

    static String reasonFor(int exitCode) {
        return switch (exitCode) {
            case 3 -> UNSUPPORTED_FILESYSTEM;
            case 4 -> EXTENDED_ACL;
            default -> null;
        };
    }

    static Snapshot read(Path root, String relative, long maximum, boolean contents) throws IOException {
        // legacy name from before the rename (ADR-0013) still honoured; new name wins
        String command = System.getenv().getOrDefault("KEPLURA_LOCAL_READER",
                System.getenv().getOrDefault("INTRACORE_LOCAL_READER", "keplura-local-reader"));
        return read(command,
                root, relative, maximum, contents);
    }

    static Snapshot read(String command, Path root, String relative, long maximum, boolean contents)
            throws IOException {
        Process process = new ProcessBuilder(command, contents ? "snapshot" : "acl", root.toString(), relative,
                Long.toString(maximum)).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var expired = new AtomicBoolean();
        var timeout = TIMER.schedule(() -> { expired.set(true); process.destroyForcibly(); }, 60, TimeUnit.SECONDS);
        Path copy = null;
        try (InputStream input = new BufferedInputStream(process.getInputStream())) {
            if (!line(input).equals("ICLR1")) throw invalid();
            String[] file = line(input).split(" ", -1);
            if (file.length != 7 || !file[0].equals("FILE")) throw invalid();
            Mode mode = mode(file);
            long size = Long.parseLong(file[4]);
            long seconds = Long.parseLong(file[5]);
            int nanos = Integer.parseInt(file[6]);
            if (size < 0 || nanos < 0 || nanos >= 1_000_000_000 || maximum < 0 || contents && size > maximum) throw invalid();
            Instant modified = Instant.ofEpochSecond(seconds, nanos);
            var parents = new ArrayList<Mode>();
            for (String header; !(header = line(input)).equals("END");) {
                String[] fields = header.split(" ", -1);
                if (fields.length != 4 || !fields[0].equals("DIR") || parents.size() >= 64) throw invalid();
                parents.add(mode(fields));
            }
            if (parents.isEmpty()) throw invalid();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long copied = 0;
            if (contents) {
                copy = Files.createTempFile("keplura-local-snapshot-", ".bin");
                try (OutputStream output = Files.newOutputStream(copy)) {
                    byte[] bytes = new byte[65536];
                    for (int n; (n = input.read(bytes)) != -1;) {
                        copied = Math.addExact(copied, n);
                        if (copied > size || copied > maximum) throw invalid();
                        digest.update(bytes, 0, n); output.write(bytes, 0, n);
                    }
                }
                if (copied != size) throw invalid();
            } else if (input.read() != -1) throw invalid();
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0 || expired.get()) throw invalid();
            return new Snapshot(copy, acl(mode, parents), size, modified, HexFormat.of().formatHex(digest.digest()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (copy != null) Files.deleteIfExists(copy);
            throw new IOException("Die lokale Rechteaufnahme wurde unterbrochen.", e);
        } catch (RuntimeException | NoSuchAlgorithmException | IOException e) {
            if (copy != null) Files.deleteIfExists(copy);
            String reason = refusalReason(process, expired.get());
            throw new IOException(reason != null ? reason
                    : "Die lokale Datei konnte nicht mit sicheren Rechten übernommen werden.", e);
        } finally {
            timeout.cancel(false);
            if (process.isAlive()) process.destroyForcibly();
        }
    }
    /** Nur ein regulär beendeter Helfer mit bekanntem Code liefert einen Grund; sonst die allgemeine Meldung. */
    private static String refusalReason(Process process, boolean expired) {
        if (expired) return null;
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return reasonFor(process.exitValue());
    }

    private static Mode mode(String[] fields) throws IOException {
        int bits = Integer.parseInt(fields[1]);
        long uid = Long.parseLong(fields[2]), gid = Long.parseLong(fields[3]);
        if (bits < 0 || uid < 0 || uid > 0xffff_ffffL || gid < 0 || gid > 0xffff_ffffL) throw invalid();
        return new Mode(bits, uid, gid);
    }
    private static String line(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int n; (n = input.read()) != '\n';) {
            if (n < 0 || n > 127 || bytes.size() >= 256) throw invalid();
            bytes.write(n);
        }
        return bytes.toString(StandardCharsets.US_ASCII);
    }
    private static IOException invalid() { return new IOException("Ungültige lokale Rechteaufnahme."); }
    private static SourceConnector.FileAcl acl(Mode file, List<Mode> parents) {
        Set<String> allow = new LinkedHashSet<>(), deny = new LinkedHashSet<>();
        String uid = "posix-uid:" + file.uid(), gid = "posix-gid:" + file.gid();
        if ((file.bits() & 0400) != 0) allow.add(uid); else deny.add(uid);
        if ((file.bits() & 0040) != 0) allow.add(gid); else deny.add(gid);
        if ((file.bits() & 0004) != 0) allow.add(AccessFilter.EVERYONE);
        for (Mode directory : parents) {
            String owner = "posix-uid:" + directory.uid(), group = "posix-gid:" + directory.gid();
            if ((directory.bits() & 0100) == 0) deny.add(owner);
            if ((directory.bits() & 0010) == 0) deny.add(group);
            if ((directory.bits() & 0001) == 0) {
                allow.removeIf(p -> !(p.equals(owner) && (directory.bits() & 0100) != 0
                        || p.equals(group) && (directory.bits() & 0010) != 0));
            }
        }
        return new SourceConnector.FileAcl(allow, deny);
    }
}
