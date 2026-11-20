package com.keplura.server.search;

import com.keplura.server.auth.AuthPrincipal;
import com.keplura.server.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG-light search API (M3d): {@code POST
 * /api/v1/orgs/{orgId}/spaces/{spaceId}/knowledge-bases/{kbId}/search} with
 * {@code {query, topK}}. Lives under the kb path because the kb IS the search
 * scope — and with it inherits the session gate ({@code /api/v1/orgs/**})
 * and the uniform-404 chain rule: any caller-visible denial looks exactly
 * like "that kb doesn't exist" ({@link SearchService}).
 *
 * <p>The response carries per-hit source attribution (documentId, filename,
 * chunkIdx, text, score) plus the backend/model tags so a client (and a
 * debug log) can always tell WHERE an answer came from and which embedding
 * produced it. Scores are cosine similarity in [-1, 1].
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/spaces/{spaceId}/knowledge-bases/{kbId}/search")
public class SearchController {

    /** Request body: query text (required) and optional result cap (clamped server-side). */
    public record SearchRequest(String query, Integer topK) {
    }

    /** Error body — same generic shape as every other endpoint here. */
    public record ErrorResponse(String error) {
    }

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping
    public ResponseEntity<?> search(@PathVariable("orgId") Long orgId,
                                    @PathVariable("spaceId") Long spaceId,
                                    @PathVariable("kbId") Long kbId,
                                    @RequestBody(required = false) SearchRequest request,
                                    HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        try {
            SearchService.SearchResponse body = searchService.search(
                    principal.userId(), orgId, spaceId, kbId,
                    request == null ? null : request.query(),
                    request == null ? null : request.topK());
            return ResponseEntity.ok(body);
        } catch (SearchService.InvalidQueryException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        } catch (SearchService.SearchTargetNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (RuntimeException e) {
            // Embedding/gateway/store failure = operational outage, not user
            // error: uniform 500, internals only in the server log. Argument
            // binding problems (malformed JSON) never reach here — Spring's
            // standard 400 keeps its shape for those.
            org.slf4j.LoggerFactory.getLogger(SearchController.class)
                    .error("search request failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("search is currently unavailable"));
        }
    }

    /** Session principal guard — identical pattern as the document/kb controllers. */
    private AuthPrincipal currentPrincipal(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        Object principal = (session == null) ? null : session.getAttribute(AuthService.SESSION_PRINCIPAL_ATTR);
        if (principal instanceof AuthPrincipal p) {
            return p;
        }
        throw new IllegalStateException("protected request reached controller without session principal");
    }
}
