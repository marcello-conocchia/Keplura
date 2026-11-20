package com.keplura.server.search;

import com.keplura.server.access.AccessFilter;
import com.keplura.server.auth.AuthPrincipal;
import com.keplura.server.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Globale Suche (Strg+K) über Namen: Arbeitsbereiche, Wissensbasen, Dokumente und die
 * EIGENEN Unterhaltungen einer Organisation. Inhaltliche Suche bleibt die Wissenssuche.
 *
 * <p>Jede Abfrage trägt die Mitgliedschaft (und bei Dokumenten den ADR-0012-Rechte-
 * Filter) in SQL — Nicht-Mitglieder erhalten leere Listen, nie fremde Namen.
 * Liegt unter {@code /api/v1/orgs/**} (Session + CSRF für schreibende Anfragen).
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/search/global")
public class GlobalSearchController {

    static final int LIMIT = 8;

    public record SpaceHit(Long id, String name) {
    }

    public record KbHit(Long id, Long spaceId, String name, String spaceName) {
    }

    public record DocumentHit(Long id, Long kbId, Long spaceId, String filename, String kbName) {
    }

    public record ConversationHit(Long id, Long spaceId, String title, Long knowledgeBaseId) {
    }

    public record Result(List<SpaceHit> spaces, List<KbHit> knowledgeBases, List<DocumentHit> documents,
                         List<ConversationHit> conversations) {
    }

    // Text blocks strip trailing spaces: keep SQL token boundaries in this fragment.
    private static final String MEMBER = " exists (select 1 from membership m where m.org_id = ? and m.user_id = ?) ";

    private final JdbcTemplate jdbc;

    public GlobalSearchController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ResponseEntity<Result> search(@PathVariable("orgId") Long orgId, @RequestParam("q") String q,
                                         HttpServletRequest request) {
        Long user = user(request);
        String term = q == null ? "" : q.strip();
        if (term.isEmpty() || term.length() > 200) {
            return ResponseEntity.ok(new Result(List.of(), List.of(), List.of(), List.of()));
        }
        String like = "%" + term.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";

        List<SpaceHit> spaces = jdbc.query("""
                select s.id, s.name from space s
                where s.org_id = ? and lower(s.name) like ? escape '\\' and """ + MEMBER + """
                order by s.name limit ?""",
                (rs, n) -> new SpaceHit(rs.getLong(1), rs.getString(2)), orgId, like, orgId, user, LIMIT);

        List<KbHit> kbs = jdbc.query("""
                select k.id, k.space_id, k.name, s.name from knowledge_base k join space s on s.id = k.space_id
                where s.org_id = ? and lower(k.name) like ? escape '\\' and """ + MEMBER + """
                order by k.name limit ?""",
                (rs, n) -> new KbHit(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4)),
                orgId, like, orgId, user, LIMIT);

        List<DocumentHit> docs = jdbc.query("""
                select d.id, d.knowledge_base_id, k.space_id, d.filename, k.name
                from kb_document d join knowledge_base k on k.id = d.knowledge_base_id join space s on s.id = k.space_id
                where s.org_id = ? and lower(d.filename) like ? escape '\\' and """ + MEMBER + """
                  and """ + AccessFilter.sql("d.id") + """
                order by d.filename limit ?""",
                (rs, n) -> new DocumentHit(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5)),
                orgId, like, orgId, user, user, user, LIMIT);

        List<ConversationHit> chats = jdbc.query("""
                select c.id, c.space_id, c.title, c.knowledge_base_id from chat_conversation c
                where c.org_id = ? and c.created_by = ? and lower(c.title) like ? escape '\\' and """ + MEMBER + """
                order by c.updated_at desc limit ?""",
                (rs, n) -> new ConversationHit(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        (Long) rs.getObject(4, Long.class)),
                orgId, user, like, orgId, user, LIMIT);

        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new Result(spaces, kbs, docs, chats));
    }

    private static Long user(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object p = session == null ? null : session.getAttribute(AuthService.SESSION_PRINCIPAL_ATTR);
        if (p instanceof AuthPrincipal ap) return ap.userId();
        throw new IllegalStateException("protected request without principal");
    }
}
