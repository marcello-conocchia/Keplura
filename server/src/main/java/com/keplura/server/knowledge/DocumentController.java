package com.keplura.server.knowledge;

import com.keplura.server.auth.AuthPrincipal;
import com.keplura.server.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Document API (M3c) — the third tenant level under
 * {@code /api/v1/orgs/{orgId}/spaces/{spaceId}/knowledge-bases}. Covered by
 * the session gate automatically (same prefix, {@code SessionAuthConfig});
 * all tenant checks are per-request in {@link DocumentService}, so ids in
 * any path position are coordinates, never credentials.
 *
 * <p>Upload contract: multipart/form-data, single part named {@code file}.
 * The extension whitelist + size limit + filename sanitisation run
 * server-side ({@link DocumentUploadPolicy}); the client part Content-Type
 * is IGNORED (type truth = whitelisted extension). The bytes go to a
 * server-generated UUID path ({@link DocumentStorage}); DELETE and content
 * updates are documented Non-Goals of M3c (mirrors the Spaces precedent).
 *
 * <p>M3d additions: {@code indexStatus} on every document row tells the
 * client honestly whether the stored bytes are searchable (INDEXED) or not
 * (NOT_INDEXED for historical rows without chunks); MVP-5 replaced the old
 * PARSER_PENDING marker with real pdf/docx extraction (Apache Tika, see
 * {@link com.keplura.server.search.TikaDocumentTextExtractor}) — the one
 * remaining non-searchable binary state is NO_TEXT_LAYER (a valid
 * pdf/docx without extractable text, e.g. a scan). Downloads additionally
 * carry {@code X-Content-Type-Options: nosniff} (hardening (c)) so no
 * browser ever re-interprets stored bytes as an active type.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/spaces/{spaceId}/knowledge-bases/{kbId}/documents")
public class DocumentController {

    /** Public shape of a document row (NEVER storedPath — server layout is not API surface). */
    public record DocumentResponse(Long id, Long kbId, String filename, String contentType,
                                   long sizeBytes, String sha256, Long uploadedBy,
                                   com.keplura.server.search.DocumentIndexingService.IndexStatus indexStatus,
                                   java.time.Instant createdAt) {
    }

    /** Error body — same generic shape as every other endpoint here. */
    public record ErrorResponse(String error) {
    }

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping
    public ResponseEntity<?> upload(@PathVariable("orgId") Long orgId,
                                    @PathVariable("spaceId") Long spaceId,
                                    @PathVariable("kbId") Long kbId,
                                    @RequestParam(value = "file", required = false) MultipartFile file,
                                    HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("file part is required"));
        }
        try {
            Document doc = documentService.upload(principal.userId(), orgId, spaceId, kbId,
                    file.getOriginalFilename(), file.getSize(), file.getInputStream());
            return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(doc));
        } catch (DocumentUploadPolicy.RejectedTypeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("file type is not accepted"));
        } catch (DocumentUploadPolicy.InvalidDocumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("document is not valid"));
        } catch (com.keplura.server.search.DocumentIndexingService.UnparseableDocumentException e) {
            // M3d: bytes stored but NOT decodable as their claimed text type —
            // the upload transaction rolled back (row + chunks + file orphaned
            // invisibly), so a clean 400 is both honest and side-effect-free.
            return ResponseEntity.badRequest().body(new ErrorResponse("text document is not valid UTF-8"));
        } catch (com.keplura.server.search.TikaDocumentTextExtractor.ExtractionBusyException e) {
            // MVP-5: parser pool saturated — retrying the SAME upload later may
            // succeed, so this is the only extraction failure that is NOT a
            // 400 (nothing about the document is wrong; capacity is).
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("document extraction is busy, try again shortly"));
        } catch (com.keplura.server.search.TikaDocumentTextExtractor.ExtractionFailureException e) {
            // MVP-5 (pdf/docx): corrupt/encrypted/over-limit — transaction
            // rolled back like every other rejection; message is generic on
            // purpose (parser internals and exact limit values are not API
            // surface; the server log carries the detail).
            return ResponseEntity.badRequest().body(new ErrorResponse("document could not be parsed"));
        } catch (DocumentUploadPolicy.TooLargeException e) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(new ErrorResponse("document exceeds the upload size limit"));
        } catch (DocumentService.DocumentNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            // part vanished mid-read (client abort) — a 400-class input problem, not a crash
            return ResponseEntity.badRequest().body(new ErrorResponse("could not read uploaded part"));
        }
    }

    /** Re-index a document (uploader or org OWNER): 200 (sync) / 202 (async) / 403 / 404. */
    @PostMapping("/{documentId}/reindex")
    public ResponseEntity<?> reindex(@PathVariable("orgId") Long orgId,
                                     @PathVariable("spaceId") Long spaceId,
                                     @PathVariable("kbId") Long kbId,
                                     @PathVariable("documentId") Long documentId,
                                     HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        try {
            Document doc = documentService.reindex(principal.userId(), orgId, spaceId, kbId, documentId);
            HttpStatus status = Document.INDEX_STATUS_PENDING.equals(doc.getIndexStatus())
                    ? HttpStatus.ACCEPTED : HttpStatus.OK;
            return ResponseEntity.status(status).body(toResponse(doc));
        } catch (DocumentService.DocumentForbiddenException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("only the uploader or an org owner may re-index this document"));
        } catch (DocumentService.DocumentNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (com.keplura.server.search.DocumentIndexingService.UnparseableDocumentException
                 | com.keplura.server.search.TikaDocumentTextExtractor.ExtractionFailureException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("document could not be parsed"));
        } catch (DocumentStorage.UnreadableDocumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Deletes a document (uploader or org OWNER): 204 / 403 / uniform 404. */
    @DeleteMapping("/{documentId}")
    public ResponseEntity<?> delete(@PathVariable("orgId") Long orgId,
                                    @PathVariable("spaceId") Long spaceId,
                                    @PathVariable("kbId") Long kbId,
                                    @PathVariable("documentId") Long documentId,
                                    HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        try {
            documentService.delete(principal.userId(), orgId, spaceId, kbId, documentId);
            return ResponseEntity.noContent().build();
        } catch (DocumentService.DocumentForbiddenException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("only the uploader or an org owner may delete this document"));
        } catch (DocumentService.DocumentNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping
    public ResponseEntity<?> list(@PathVariable("orgId") Long orgId,
                                  @PathVariable("spaceId") Long spaceId,
                                  @PathVariable("kbId") Long kbId,
                                  HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        try {
            List<DocumentResponse> body = documentService
                    .list(principal.userId(), orgId, spaceId, kbId).stream()
                    .map(this::toResponse)
                    .toList();
            return ResponseEntity.ok(body);
        } catch (DocumentService.DocumentNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Streams the stored bytes back with the server-mapped Content-Type (the
     * one whitelisted for the extension — never the client's part header) and
     * an attachment disposition carrying the sanitised display name. Tenant
     * chain + path containment are re-verified per-request; unreadable rows
     * 404 identically to foreign ones.
     */
    @GetMapping("/{documentId}/download")
    public ResponseEntity<?> download(@PathVariable("orgId") Long orgId,
                                      @PathVariable("spaceId") Long spaceId,
                                      @PathVariable("kbId") Long kbId,
                                      @PathVariable("documentId") Long documentId,
                                      HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        try {
            DocumentService.Downloadable file =
                    documentService.download(principal.userId(), orgId, spaceId, kbId, documentId);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(file.contentType()))
                    .contentLength(file.sizeBytes())
                    // M3d hardening (c): never let a browser sniff stored bytes
                    // into a different (active) type than the one we mapped —
                    // the served type IS the truth, nosniff enforces it.
                    .header("X-Content-Type-Options", "nosniff")
                    // CSP sandbox: even if a browser navigates straight to
                    // this URL, stored bytes render in an opaque origin —
                    // no scripting, no same-origin powers (hardening (c)).
                    .header("Content-Security-Policy", "sandbox")
                    .header("X-Index-Status", file.indexStatus().name())
                    // display name comes from the sanitiser (letters/digits + few separators, no
                    // quotes/semicolons/control chars); Spring encodes non-ASCII per RFC 5987
                    // (filename*=UTF-8''…) so Umlaute survive without header injection surface.
                    .header(HttpHeaders.CONTENT_DISPOSITION, attachmentHeader(file.filename()))
                    .body(new FileSystemResource(file.file()));
        } catch (DocumentService.DocumentNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Container-level limit breach: Tomcat rejects while parsing, before the controller body runs. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> uploadTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("document exceeds the upload size limit"));
    }

    /** Malformed multipart (missing "file" part, bad boundary) — uniform 400 shape, no stack leakage. */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> malformedMultipart(MultipartException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("multipart upload is malformed"));
    }

    /** No "file" part at all: same generic 400 body as every other rejection here. */
    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> missingFilePart(
            org.springframework.web.multipart.support.MissingServletRequestPartException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("multipart upload is malformed"));
    }

    private DocumentResponse toResponse(Document doc) {
        return new DocumentResponse(doc.getId(), doc.getKbId(), doc.getFilename(),
                doc.getContentType(), doc.getSizeBytes(), doc.getSha256(), doc.getUploadedBy(),
                documentService.indexStatusOf(doc), doc.getCreatedAt());
    }

    /**
     * Session principal for a protected request — identical guard as in the
     * space/kb controllers (interceptor 401s first; this is a wiring guard).
     */
    private AuthPrincipal currentPrincipal(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        Object principal = (session == null) ? null : session.getAttribute(AuthService.SESSION_PRINCIPAL_ATTR);
        if (principal instanceof AuthPrincipal p) {
            return p;
        }
        throw new IllegalStateException("protected request reached controller without session principal");
    }

    /**
     * Content-Disposition for a sanitised display name. ASCII names keep the exact
     * historic form {@code attachment; filename="x"}; names with Umlauten etc. add an
     * RFC 5987 {@code filename*} (UTF-8, percent-encoded) and an ASCII fallback in which
     * every non-ASCII character becomes '_'. The sanitiser already excludes quotes,
     * semicolons and control characters, so neither part can break out of the header.
     */
    static String attachmentHeader(String name) {
        boolean ascii = name.chars().allMatch(c -> c >= 0x20 && c < 0x7f);
        if (ascii) return "attachment; filename=\"" + name + "\"";
        StringBuilder fallback = new StringBuilder(name.length());
        name.codePoints().forEach(cp -> fallback.append(cp >= 0x20 && cp < 0x7f ? (char) cp : '_'));
        StringBuilder encoded = new StringBuilder();
        for (byte b : name.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int v = b & 0xff;
            boolean attrChar = (v >= 'a' && v <= 'z') || (v >= 'A' && v <= 'Z') || (v >= '0' && v <= '9')
                    || "!#$&+-.^_`|~".indexOf(v) >= 0;
            if (attrChar) encoded.append((char) v);
            else encoded.append('%').append(String.format("%02X", v));
        }
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }
}
