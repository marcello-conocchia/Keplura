package com.keplura.server.datasource;

import com.keplura.server.search.TikaDocumentTextExtractor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * RISK-02 — liest den Inhalt EINER breit lesbaren Datei über den Konnektor und
 * zählt sensible Muster ({@link ContentPatterns}). Rein lokal, ohne OCR.
 *
 * <p>Grenzen: höchstens {@code max-bytes} werden gelesen, Office/PDF laufen über
 * den Tika-Extraktor mit eigener Seitengrenze, die ganze Datei (Lesen, Parsen,
 * Zählen) hat ein Zeitlimit. Jeder Fehler ergibt „nicht prüfbar“ mit einem kurzen
 * Grund, nie „unauffällig“. Werte und Pfade werden nicht geloggt.
 *
 * <p>Höchstens zwei Lese-Threads: einer arbeitet, einer darf nach einem Zeitlimit noch
 * nachlaufen. Hängen beide, ist die nächste Datei „nicht prüfbar“, statt Threads anzuhäufen.
 */
@Component
public class ExposureContentInspector {

    public static final String CHECKED = "CHECKED";
    public static final String NOT_CHECKABLE = "NOT_CHECKABLE";

    public record Result(String state, String reason, List<ContentPatterns.Hit> hits) {
        static Result notCheckable(String reason) {
            return new Result(NOT_CHECKABLE, reason, List.of());
        }
    }

    static final java.util.Set<String> TEXT_TYPES = java.util.Set.of("txt", "md", "csv");

    private final TikaDocumentTextExtractor extractor;
    private final boolean enabled;
    private final long maxBytes;
    private final int maxPages;
    private final int timeoutSeconds;
    private final int maxFiles;
    private final int maxChars;
    private final long maxRunMillis;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(1), r -> {
                Thread t = new Thread(r, "exposure-content");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());

    @org.springframework.beans.factory.annotation.Autowired
    public ExposureContentInspector(TikaDocumentTextExtractor extractor,
                                    @Value("${keplura.exposure.content.enabled:true}") boolean enabled,
                                    @Value("${keplura.exposure.content.max-bytes:20971520}") long maxBytes,
                                    @Value("${keplura.exposure.content.max-pages:200}") int maxPages,
                                    @Value("${keplura.exposure.content.timeout-seconds:20}") int timeoutSeconds,
                                    @Value("${keplura.exposure.content.max-files:2000}") int maxFiles,
                                    @Value("${keplura.parser.extracted-chars:2000000}") int maxChars,
                                    @Value("${keplura.exposure.content.max-run-minutes:60}") int maxRunMinutes) {
        if (maxBytes <= 0 || maxPages <= 0 || timeoutSeconds <= 0 || maxFiles <= 0 || maxChars <= 0
                || maxRunMinutes <= 0) {
            throw new IllegalArgumentException("keplura.exposure.content.* limits must be > 0");
        }
        this.extractor = extractor;
        this.enabled = enabled;
        this.maxBytes = maxBytes;
        this.maxPages = maxPages;
        this.timeoutSeconds = timeoutSeconds;
        this.maxFiles = maxFiles;
        this.maxChars = maxChars;
        this.maxRunMillis = maxRunMinutes * 60_000L;
    }

    /** Test-Konstruktor mit den Standardwerten für Zeichen und Laufzeit. */
    ExposureContentInspector(TikaDocumentTextExtractor extractor, boolean enabled, long maxBytes, int maxPages,
                             int timeoutSeconds, int maxFiles) {
        this(extractor, enabled, maxBytes, maxPages, timeoutSeconds, maxFiles, 2_000_000, 60);
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    public boolean enabled() {
        return enabled;
    }

    public int maxFiles() {
        return maxFiles;
    }

    /** Gesamtzeit je Prüflauf; danach zählen die übrigen Dateien als nicht geprüft (skipped). */
    public long maxRunMillis() {
        return maxRunMillis;
    }

    /** Prüft eine Datei. Wirft nie; Fehler werden zu „nicht prüfbar“. */
    public Result inspect(SourceConnector connector, SourceConnector.Entry entry) {
        if (entry.sizeBytes() > maxBytes) return Result.notCheckable("zu groß für die Inhaltsprüfung");
        Future<Result> f;
        try {
            f = worker.submit(() -> readWithRetry(connector, entry));
        } catch (RejectedExecutionException e) {
            return Result.notCheckable("Server ausgelastet, bitte erneut prüfen");
        } catch (RuntimeException e) {
            return Result.notCheckable("Datei konnte nicht gelesen werden");
        }
        try {
            // etwas Luft über dem Parser-Zeitlimit, damit dessen genauere Meldung gewinnt
            return f.get(timeoutSeconds + 5L, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            return Result.notCheckable("Zeitlimit überschritten");
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            return Result.notCheckable("Prüfung abgebrochen");
        } catch (ExecutionException e) {
            return Result.notCheckable("Datei konnte nicht gelesen werden");
        }
    }

    /** Der Prüf-Parser ist kurz belegt (abgebrochener Vorgänger läuft nach): kurz warten, dann aufgeben. */
    private Result readWithRetry(SourceConnector connector, SourceConnector.Entry entry) throws InterruptedException {
        Result r = read(connector, entry);
        for (int i = 0; i < 3 && BUSY.equals(r.reason()); i++) {
            Thread.sleep(1000);
            r = read(connector, entry);
        }
        return r;
    }

    private static final String BUSY = "Server ausgelastet, bitte erneut prüfen";

    private Result read(SourceConnector connector, SourceConnector.Entry entry) {
        String path = entry.relativePath();
        String ext = extensionOf(path);
        boolean text = TEXT_TYPES.contains(ext);
        if (!text && !TikaDocumentTextExtractor.PARSED_EXTENSIONS.contains(ext)) {
            return Result.notCheckable("Dateityp wird nicht geprüft");
        }
        Path tmp = null;
        try (InputStream in = connector.open(path)) {
            String content;
            if (text) {
                byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
                if (bytes.length > maxBytes) return Result.notCheckable("zu groß für die Inhaltsprüfung");
                content = decodeText(bytes);
                if (content == null) return Result.notCheckable("unbekannte Zeichenkodierung");
            } else {
                tmp = Files.createTempFile("keplura-exposure-", "." + ext);
                if (!copyBounded(in, tmp)) return Result.notCheckable("zu groß für die Inhaltsprüfung");
                content = extractor.extractForInspection(path, tmp, maxPages, timeoutSeconds).text();
            }
            if (content.length() > maxChars) return Result.notCheckable("zu groß für die Inhaltsprüfung");
            if (content.isBlank()) return Result.notCheckable("kein Text (z. B. Scan)");
            return new Result(CHECKED, null, ContentPatterns.scan(content));
        } catch (TikaDocumentTextExtractor.BlankExtractedTextException e) {
            return Result.notCheckable("kein Text (z. B. Scan)");
        } catch (TikaDocumentTextExtractor.TruncatedExtractionException e) {
            String m = String.valueOf(e.getMessage());
            return Result.notCheckable(m.contains("timed out") ? "Zeitlimit überschritten"
                    : m.contains("page limit") || m.contains("slide limit") ? "zu viele Seiten für die Inhaltsprüfung"
                    : "zu groß für die Inhaltsprüfung");
        } catch (TikaDocumentTextExtractor.ExtractionBusyException e) {
            return Result.notCheckable(BUSY);
        } catch (TikaDocumentTextExtractor.ExtractionFailureException e) {
            return Result.notCheckable("beschädigt oder verschlüsselt");
        } catch (IOException | RuntimeException e) {
            return Result.notCheckable("Datei konnte nicht gelesen werden");
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    tmp.toFile().deleteOnExit();
                }
            }
        }
    }

    /**
     * UTF-8, oder UTF-16 mit BOM bzw. erkennbaren Nullbytes (Windows-„Unicode-Text“). Sonst
     * Windows-1252, wenn UTF-8 nicht passt. {@code null} = Kodierung nicht sicher erkennbar.
     */
    static String decodeText(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xfe)
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        if (b.length >= 2 && (b[0] & 0xff) == 0xfe && (b[1] & 0xff) == 0xff)
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        int sample = Math.min(b.length, 4096), evenNul = 0, oddNul = 0;
        for (int i = 0; i < sample; i++) {
            if (b[i] == 0) {
                if (i % 2 == 0) evenNul++; else oddNul++;
            }
        }
        int half = Math.max(1, sample / 2);
        if (oddNul * 10 > half * 3 && evenNul * 10 < oddNul) return new String(b, StandardCharsets.UTF_16LE);
        if (evenNul * 10 > half * 3 && oddNul * 10 < evenNul) return new String(b, StandardCharsets.UTF_16BE);
        if (evenNul + oddNul > 0) return null; // Binärinhalt oder unbekannte Kodierung
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(b)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(b, java.nio.charset.Charset.forName("windows-1252"));
        }
    }

    /** false = Datei größer als {@code maxBytes} (Rest wird nicht gelesen). */
    private boolean copyBounded(InputStream in, Path target) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        try (OutputStream out = Files.newOutputStream(target)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) return false;
                out.write(buf, 0, n);
            }
        }
        return true;
    }

    static String extensionOf(String path) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        return dot < 0 || dot < slash ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
