package com.keplura.server.quality;

import com.keplura.server.auth.AuthPrincipal;
import com.keplura.server.auth.AuthService;
import com.keplura.server.chat.ChatProperties;
import com.keplura.server.knowledge.KnowledgeBaseRepository;
import com.keplura.server.search.SearchHit;
import com.keplura.server.search.SearchService;
import com.keplura.server.space.SpaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DS-5 (ADR-0012 §6) — Antwortqualität messbar machen.
 *
 * <p>Ein Prüflauf stellt jede Testfrage über DIESELBE Suche wie der Chat (gleiche
 * Trefferzahl {@code ragTopK}, gleiche Rechte-Filter — mit den Rechten der
 * prüfenden Person) und zählt, ob die erwartete Quelle unter den Treffern ist.
 * Kein Sprachmodell wird aufgerufen: gemessen wird, ob die richtige Stelle
 * gefunden wird — die Voraussetzung jeder belegten Antwort.
 *
 * <p>Daumen-Statistik nur als Zahlen je Organisation, keine Chat-Inhalte (Datenschutz).
 * Unter {@code /api/v1/admin/**}: Session + CSRF + globale ADMIN-Rolle.
 */
@RestController
@RequestMapping("/api/v1/admin/quality")
public class QualityAdminController {

    static final int MAX_QUESTIONS = 200;

    public static class InputException extends RuntimeException {
        public InputException(String m) {
            super(m);
        }
    }

    public record ErrorResponse(String error) {
    }

    public record QuestionInput(Long orgId, Long spaceId, Long knowledgeBaseId, String question,
                                String expectedDocument, String expectedPhrase) {
    }

    public record ImportInput(Long orgId, Long spaceId, Long knowledgeBaseId, String csv) {
    }

    public record RunInput(Long orgId, Long spaceId, Long knowledgeBaseId) {
    }

    public record QuestionView(Long id, String question, String expectedDocument, String expectedPhrase,
                               Boolean lastHit, Integer lastRank, String lastTopDocument) {
    }

    public record RunView(Long id, Instant startedAt, Instant finishedAt, int total, int hits, int percent) {
    }

    public record Overview(List<QuestionView> questions, List<RunView> runs) {
    }

    public record ImportResult(int imported, int skipped, List<String> problems) {
    }

    public record FeedbackStats(long up, long down, long answersLast30Days) {
    }

    private final QualityQuestionRepository questions;
    private final QualityRunRepository runs;
    private final QualityResultRepository results;
    private final SearchService searchService;
    private final ChatProperties chatProperties;
    private final KnowledgeBaseRepository kbRepository;
    private final SpaceRepository spaceRepository;
    private final JdbcTemplate jdbc;

    public QualityAdminController(QualityQuestionRepository questions, QualityRunRepository runs,
                                  QualityResultRepository results, SearchService searchService,
                                  ChatProperties chatProperties, KnowledgeBaseRepository kbRepository,
                                  SpaceRepository spaceRepository, JdbcTemplate jdbc) {
        this.questions = questions;
        this.runs = runs;
        this.results = results;
        this.searchService = searchService;
        this.chatProperties = chatProperties;
        this.kbRepository = kbRepository;
        this.spaceRepository = spaceRepository;
        this.jdbc = jdbc;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Overview overview(@RequestParam("knowledgeBaseId") Long kbId) {
        List<QualityRun> recent = runs.findTop10ByKnowledgeBaseIdOrderByIdDesc(kbId);
        Map<Long, QualityResult> last = new HashMap<>();
        if (!recent.isEmpty()) {
            for (QualityResult r : results.findByRunId(recent.get(0).getId())) last.put(r.getQuestionId(), r);
        }
        List<QuestionView> qs = questions.findByKnowledgeBaseIdOrderByIdAsc(kbId).stream().map(q -> {
            QualityResult r = last.get(q.getId());
            return new QuestionView(q.getId(), q.getQuestion(), q.getExpectedDocument(), q.getExpectedPhrase(),
                    r == null ? null : r.isHit(), r == null ? null : r.getHitRank(), r == null ? null : r.getTopDocument());
        }).toList();
        return new Overview(qs, recent.stream().map(QualityAdminController::view).toList());
    }

    @PostMapping("/questions")
    @Transactional
    public ResponseEntity<QuestionView> add(@RequestBody QuestionInput in, HttpServletRequest request) {
        requireTarget(in == null ? null : in.orgId(), in == null ? null : in.spaceId(), in == null ? null : in.knowledgeBaseId());
        if (questions.findByKnowledgeBaseIdOrderByIdAsc(in.knowledgeBaseId()).size() >= MAX_QUESTIONS) {
            throw new InputException("Höchstens " + MAX_QUESTIONS + " Testfragen je Wissensbasis.");
        }
        QualityQuestion q = questions.saveAndFlush(new QualityQuestion(in.orgId(), in.spaceId(), in.knowledgeBaseId(),
                text(in.question(), "Frage", 2000), text(in.expectedDocument(), "Erwartete Quelle", 500),
                optional(in.expectedPhrase(), 500), currentUser(request)));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new QuestionView(q.getId(), q.getQuestion(), q.getExpectedDocument(), q.getExpectedPhrase(), null, null, null));
    }

    /** CSV/Excel-Export: je Zeile „Frage;Erwartete Quelle[;Stichwort]“ (Semikolon oder Tab). */
    @PostMapping("/questions/import")
    @Transactional
    public ImportResult importCsv(@RequestBody ImportInput in, HttpServletRequest request) {
        requireTarget(in == null ? null : in.orgId(), in == null ? null : in.spaceId(), in == null ? null : in.knowledgeBaseId());
        if (in.csv() == null || in.csv().length() > 500_000) throw new InputException("Die Liste ist leer oder zu groß.");
        int existing = questions.findByKnowledgeBaseIdOrderByIdAsc(in.knowledgeBaseId()).size();
        int imported = 0, skipped = 0;
        List<String> problems = new ArrayList<>();
        Long user = currentUser(request);
        String[] lines = in.csv().split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || (i == 0 && line.toLowerCase(Locale.ROOT).startsWith("frage"))) continue;
            String[] cols = line.split("[;\\t]", -1);
            if (cols.length < 2 || cols[0].isBlank() || cols[1].isBlank()) {
                skipped++;
                if (problems.size() < 10) problems.add("Zeile " + (i + 1) + ": Frage und Quelle werden gebraucht.");
                continue;
            }
            if (existing + imported >= MAX_QUESTIONS) {
                skipped++;
                if (problems.size() < 10) problems.add("Ab Zeile " + (i + 1) + ": Höchstzahl von " + MAX_QUESTIONS + " erreicht.");
                continue;
            }
            questions.save(new QualityQuestion(in.orgId(), in.spaceId(), in.knowledgeBaseId(),
                    clip(unquote(cols[0]), 2000), clip(unquote(cols[1]), 500),
                    cols.length > 2 && !cols[2].isBlank() ? clip(unquote(cols[2]), 500) : null, user));
            imported++;
        }
        questions.flush();
        return new ImportResult(imported, skipped, problems);
    }

    @DeleteMapping("/questions/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        questions.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/runs")
    public RunView run(@RequestBody RunInput in, HttpServletRequest request) {
        requireTarget(in == null ? null : in.orgId(), in == null ? null : in.spaceId(), in == null ? null : in.knowledgeBaseId());
        List<QualityQuestion> qs = questions.findByKnowledgeBaseIdOrderByIdAsc(in.knowledgeBaseId());
        if (qs.isEmpty()) throw new InputException("Lege zuerst Testfragen an.");
        Long user = currentUser(request);
        QualityRun run = runs.saveAndFlush(new QualityRun(in.knowledgeBaseId(), user));
        int topK = Math.max(1, chatProperties.ragTopK());
        int hits = 0;
        for (QualityQuestion q : qs) {
            Integer rank = null;
            String top = null;
            try {
                List<SearchHit> found = searchService.search(user, in.orgId(), in.spaceId(), in.knowledgeBaseId(),
                        q.getQuestion(), topK).results();
                if (!found.isEmpty()) top = found.get(0).filename();
                for (int i = 0; i < found.size(); i++) {
                    if (matches(q, found.get(i))) {
                        rank = i + 1;
                        break;
                    }
                }
            } catch (RuntimeException e) {
                top = "Fehler bei der Suche";
            }
            if (rank != null) hits++;
            results.save(new QualityResult(run.getId(), q.getId(), rank != null, rank, top));
        }
        run.finish(qs.size(), hits);
        return view(runs.saveAndFlush(run));
    }

    /** Daumen-Statistik der letzten 30 Tage für eine Organisation (nur Zahlen). */
    @GetMapping("/feedback")
    public FeedbackStats feedback(@RequestParam("orgId") Long orgId) {
        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        Map<String, Object> row = jdbc.queryForMap("""
                select
                  coalesce(sum(case when m.feedback = 'up' then 1 else 0 end), 0) as up,
                  coalesce(sum(case when m.feedback = 'down' then 1 else 0 end), 0) as down,
                  count(*) as answers
                from chat_message m
                join chat_conversation c on c.id = m.conversation_id
                where c.org_id = ? and m.role = 'assistant' and m.created_at >= ?
                """, orgId, java.sql.Timestamp.from(since));
        return new FeedbackStats(num(row.get("up")), num(row.get("down")), num(row.get("answers")));
    }

    @ExceptionHandler(InputException.class)
    public ResponseEntity<ErrorResponse> invalid(InputException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
    }

    // ------------------------------------------------------------------ helpers

    static boolean matches(QualityQuestion q, SearchHit hit) {
        String doc = q.getExpectedDocument().toLowerCase(Locale.ROOT);
        if (hit.filename() == null || !hit.filename().toLowerCase(Locale.ROOT).contains(doc)) return false;
        String phrase = q.getExpectedPhrase();
        return phrase == null || phrase.isBlank()
                || (hit.text() != null && hit.text().toLowerCase(Locale.ROOT).contains(phrase.toLowerCase(Locale.ROOT)));
    }

    private void requireTarget(Long orgId, Long spaceId, Long kbId) {
        if (orgId == null || spaceId == null || kbId == null) throw new InputException("Bitte eine Wissensbasis wählen.");
        var space = spaceRepository.findById(spaceId).orElse(null);
        var kb = kbRepository.findById(kbId).orElse(null);
        if (space == null || kb == null || !space.getOrgId().equals(orgId) || !kb.getSpaceId().equals(spaceId)) {
            throw new InputException("Wissensbasis, Arbeitsbereich und Organisation passen nicht zusammen.");
        }
    }

    private static RunView view(QualityRun r) {
        int pct = r.getTotal() == 0 ? 0 : Math.round(100f * r.getHits() / r.getTotal());
        return new RunView(r.getId(), r.getStartedAt(), r.getFinishedAt(), r.getTotal(), r.getHits(), pct);
    }

    private static String text(String v, String label, int max) {
        if (v == null || v.isBlank()) throw new InputException(label + " fehlt.");
        String t = v.strip();
        if (t.length() > max) throw new InputException(label + " ist zu lang.");
        return t;
    }

    private static String optional(String v, int max) {
        if (v == null || v.isBlank()) return null;
        return clip(v.strip(), max);
    }

    private static String clip(String v, int max) {
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static String unquote(String v) {
        String t = v.strip();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1).replace("\"\"", "\"");
        return t.strip();
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static Long currentUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object p = session == null ? null : session.getAttribute(AuthService.SESSION_PRINCIPAL_ATTR);
        if (p instanceof AuthPrincipal ap) return ap.userId();
        throw new IllegalStateException("admin request without principal");
    }
}
