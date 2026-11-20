package com.keplura.server.search.ocr;

import com.keplura.server.search.TikaDocumentTextExtractor.TruncatedExtractionException;
import jakarta.annotation.PreDestroy;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * OCR-01 — lokale Texterkennung für PDF-Seiten ohne Textschicht (Scans).
 *
 * <p>Ablauf je Seite: PDFBox rendert die Seite als Graustufenbild in eine
 * temporäre Datei mit festem Namensmuster, Tesseract liest sie mit festen
 * Argumenten ({@code <bild> stdout -l <sprachen> --psm 3}). Kein Datei- oder
 * Benutzername erreicht je die Kommandozeile, keine Shell, kein Netzwerk.
 *
 * <h2>Grenzen</h2>
 * <ul>
 *   <li>Genau EIN OCR-Arbeiter (CPU-schwer); weitere Dokumente warten.</li>
 *   <li>{@code keplura.ocr.max-pages} Seiten je Dokument (Rest bleibt ohne Text,
 *       Ergebnis meldet das ehrlich).</li>
 *   <li>Zeitlimit je Seite (Prozess wird hart beendet) und je Dokument.</li>
 *   <li>Pixelgrenze je Seite: übergroße Formate werden herunterskaliert.</li>
 *   <li>Ausgabe je Seite begrenzt.</li>
 *   <li>Hängt eine Seite nach Ablauf ihres Zeitlimits noch im Arbeiter (das
 *       PDFBox-Rendering läuft im Serverprozess und reagiert nicht auf
 *       Unterbrechung), werden weitere Seiten sofort übersprungen, statt je
 *       Seite das volle Zeitlimit dahinter zu warten. OCR erholt sich von selbst,
 *       sobald die Seite fertig ist. Als hängend gilt eine Seite erst, wenn sie
 *       auch {@link #CANCEL_GRACE_MILLIS} ms nach der Unterbrechung noch läuft;
 *       eine nur zu langsame, unterbrechbare Tesseract-Seite fällt allein aus
 *       und die übrigen Seiten werden weiter erkannt.</li>
 * </ul>
 * Fehlt Tesseract oder ist OCR abgeschaltet, ist {@link #available()} false
 * und das Dokument bleibt wie bisher {@code NO_TEXT_LAYER}.
 */
@Component
public class PdfOcr {

    private static final Logger log = LoggerFactory.getLogger(PdfOcr.class);
    private static final Pattern LANGS = Pattern.compile("[a-z_]{3,16}(\\+[a-z_]{3,16}){0,4}");
    static final long MAX_PIXELS = 40_000_000L; // ~A3 bei 300 dpi
    static final int MAX_CHARS_PER_PAGE = 100_000;
    /** So lange darf eine unterbrochene Seite noch aufräumen, bevor der Arbeiter als hängend gilt. */
    static final long CANCEL_GRACE_MILLIS = 2_000;

    /** Ergebnis: erkannter Text je Seitenindex (0-basiert); {@code skippedPages} = über dem Limit. */
    public record Result(Map<Integer, String> pages, int skippedPages, int failedPages) {
    }

    private final boolean enabled;
    private final String command;
    private final String languages;
    private final int dpi;
    private final int maxPages;
    private final int pageTimeoutSeconds;
    private final int totalTimeoutSeconds;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ocr-worker");
        t.setDaemon(true);
        return t;
    });
    private volatile Boolean available;
    /** Seite, deren Aufrufer nach dem Zeitlimit aufgegeben hat, die aber noch im Arbeiter läuft. */
    private final java.util.concurrent.atomic.AtomicReference<PageTask> abandoned =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** Eine Seite auf dem OCR-Arbeiter; merkt sich, ob sie begonnen hat und fertig ist. */
    private final class PageTask implements java.util.concurrent.Callable<String> {
        private final Path source;
        private final int page;
        final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        /** Wird im {@code finally} von {@link #call()} heruntergezählt, wenn die Seite wirklich fertig ist. */
        final java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);

        PageTask(Path source, int page) {
            this.source = source;
            this.page = page;
        }

        @Override
        public String call() throws IOException {
            started.set(true);
            try {
                return recognizePage(source, page);
            } finally {
                done.set(true);
                abandoned.compareAndSet(this, null);
                finished.countDown();
            }
        }
    }

    /** true = der Arbeiter steckt noch in einer aufgegebenen Seite. */
    private boolean workerStuck() {
        PageTask stuck = abandoned.get();
        return stuck != null && !stuck.done.get();
    }

    public PdfOcr(@Value("${keplura.ocr.enabled:true}") boolean enabled,
                  @Value("${keplura.ocr.command:tesseract}") String command,
                  @Value("${keplura.ocr.languages:deu+eng}") String languages,
                  @Value("${keplura.ocr.dpi:300}") int dpi,
                  @Value("${keplura.ocr.max-pages:100}") int maxPages,
                  @Value("${keplura.ocr.page-timeout-seconds:60}") int pageTimeoutSeconds,
                  @Value("${keplura.ocr.total-timeout-seconds:900}") int totalTimeoutSeconds) {
        if (!LANGS.matcher(languages).matches()) {
            throw new IllegalArgumentException("keplura.ocr.languages must look like deu+eng");
        }
        if (dpi < 72 || dpi > 600 || maxPages <= 0 || pageTimeoutSeconds <= 0 || totalTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("invalid keplura.ocr.* limits");
        }
        this.enabled = enabled;
        this.command = command;
        this.languages = languages;
        this.dpi = dpi;
        this.maxPages = maxPages;
        this.pageTimeoutSeconds = pageTimeoutSeconds;
        this.totalTimeoutSeconds = totalTimeoutSeconds;
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    /** OCR eingeschaltet und Tesseract mit allen Sprachen vorhanden (einmal geprüft, Ergebnis gemerkt). */
    public boolean available() {
        if (!enabled) return false;
        Boolean a = available;
        if (a == null) {
            a = probe();
            available = a;
            log.info("ocr {}", a ? "available (" + languages + ")" : "not available — scanned PDFs stay NO_TEXT_LAYER");
        }
        return a;
    }

    private boolean probe() {
        try {
            Run r = exec(List.of(command, "--list-langs"), 10);
            if (r.exit != 0) return false;
            List<String> have = r.stdout.lines().map(String::trim).toList();
            for (String lang : languages.split("\\+")) {
                if (!have.contains(lang)) {
                    log.warn("ocr language '{}' is not installed", lang);
                    return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Erkennt die angegebenen Seiten (0-basiert) der PDF-Datei. Maximal
     * {@code limit} (höchstens {@code keplura.ocr.max-pages}) Seiten.
     */
    public Result recognize(Path pdf, List<Integer> pageIndexes, int limit) {
        return recognize(pdf, pageIndexes, limit, totalTimeoutSeconds);
    }

    /**
     * Wie oben, mit zusätzlichem Zeitbudget (Sekunden) für Aufrufe, deren Antwort ein
     * Mensch synchron abwartet (Chat-Anhang). Nach Ablauf bleiben restliche Seiten ohne Text.
     */
    public Result recognize(Path pdf, List<Integer> pageIndexes, int limit, int budgetSeconds) {
        int cap = Math.min(limit, maxPages);
        List<Integer> todo = pageIndexes.size() > cap ? pageIndexes.subList(0, cap) : pageIndexes;
        Map<Integer, String> out = new LinkedHashMap<>();
        int failed = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.min(totalTimeoutSeconds, Math.max(1, budgetSeconds)));
        Path copy = null;
        try {
            // eigene Kopie: ein abgebrochener Arbeiter hält nie die gespeicherte Datei offen
            copy = Files.createTempFile("keplura-ocr-", ".pdf");
            Files.copy(pdf, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            final Path source = copy;
            for (int i = 0; i < todo.size(); i++) {
                int page = todo.get(i);
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    failed += todo.size() - i; // Rest bleibt ohne Text
                    log.info("ocr total time limit reached");
                    break;
                }
                if (workerStuck()) {
                    failed += todo.size() - i; // nicht hinter einer hängenden Seite anstellen
                    log.warn("ocr worker still busy with an abandoned page; remaining pages skipped");
                    break;
                }
                long wait = Math.min(TimeUnit.SECONDS.toNanos(pageTimeoutSeconds), left);
                PageTask task = new PageTask(source, page);
                Future<String> f = worker.submit(task);
                try {
                    out.put(page, f.get(wait, TimeUnit.NANOSECONDS));
                } catch (TimeoutException e) {
                    f.cancel(true); // unterbricht waitFor → Prozess wird hart beendet
                    failed++;
                    // Eine unterbrechbare Seite räumt noch kurz auf (Prozess beenden, PNG löschen).
                    // Nur wenn sie danach weiterläuft (Rendering reagiert nicht auf Unterbrechung),
                    // gilt der Arbeiter als hängend; sonst fällt nur diese eine Seite aus.
                    if (task.started.get()) {
                        try {
                            if (!task.finished.await(CANCEL_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                                abandoned.set(task);
                            }
                        } catch (InterruptedException ie) {
                            if (!task.done.get()) abandoned.set(task);
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof TruncatedExtractionException limitHit) {
                        throw limitHit; // a character cap rejects the whole document, never a partial page
                    }
                    log.debug("ocr failed for one page", e.getCause());
                    failed++;
                } catch (InterruptedException e) {
                    f.cancel(true);
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (IOException e) {
            failed = todo.size();
        } finally {
            if (copy != null) {
                try {
                    Files.deleteIfExists(copy);
                } catch (IOException ignored) {
                    copy.toFile().deleteOnExit();
                }
            }
        }
        return new Result(out, pageIndexes.size() - todo.size(), failed);
    }

    private String recognizePage(Path pdf, int page) throws IOException {
        Path png = Files.createTempFile("keplura-ocr-page-", ".png");
        try {
            try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
                PDRectangle box = doc.getPage(page).getCropBox();
                float scale = dpi / 72f;
                double pixels = (double) box.getWidth() * scale * box.getHeight() * scale;
                if (pixels > MAX_PIXELS) scale *= (float) Math.sqrt(MAX_PIXELS / pixels);
                BufferedImage img = new PDFRenderer(doc).renderImage(page, scale, ImageType.GRAY);
                ImageIO.write(img, "png", png.toFile());
            }
            Run r = exec(List.of(command, png.toString(), "stdout", "-l", languages, "--psm", "3"), pageTimeoutSeconds);
            if (r.exit != 0) throw new IOException("tesseract exit " + r.exit);
            return r.stdout;
        } finally {
            Files.deleteIfExists(png);
        }
    }

    // ------------------------------------------------------------------ process

    record Run(int exit, String stdout) {
    }

    Run exec(List<String> args, int timeoutSeconds) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(args));
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File(nullDevice())));
        pb.environment().put("OMP_THREAD_LIMIT", "1"); // ein Kern je Seite, Server bleibt bedienbar
        Process p = pb.start();
        try {
            var reader = new java.util.concurrent.CompletableFuture<String>();
            Thread.ofVirtual().start(() -> {
                try (InputStream input = p.getInputStream()) {
                    reader.complete(readCapped(input));
                } catch (Throwable t) {
                    reader.completeExceptionally(t);
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            // Observe an output-limit failure immediately, even if a hostile or
            // stuck process keeps running after its stdout pipe was closed.
            String text = reader.get(timeoutSeconds, TimeUnit.SECONDS);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !p.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                throw new IOException("ocr process timed out");
            }
            return new Run(p.exitValue(), text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ocr interrupted");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TruncatedExtractionException limitHit) {
                throw limitHit;
            }
            throw new IOException("ocr output could not be read");
        } catch (TimeoutException e) {
            throw new IOException("ocr output timed out");
        } finally {
            if (p.isAlive()) p.destroyForcibly();
        }
    }

    static String readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        int limit = MAX_CHARS_PER_PAGE * 4; // UTF-8 worst case
        while ((n = in.read(b)) >= 0) {
            if ((long) buf.size() + n > limit) {
                throw new TruncatedExtractionException(
                        "document exceeds the OCR page character limit");
            }
            buf.write(b, 0, n);
        }
        String s = buf.toString(StandardCharsets.UTF_8);
        if (s.length() > MAX_CHARS_PER_PAGE) {
            throw new TruncatedExtractionException(
                    "document exceeds the OCR page character limit");
        }
        return s;
    }

    private static String nullDevice() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")
                ? "NUL" : "/dev/null";
    }
}
