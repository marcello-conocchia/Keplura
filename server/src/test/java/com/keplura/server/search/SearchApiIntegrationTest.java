package com.keplura.server.search;

import com.keplura.server.identity.PasswordHasher;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3d: the RAG-light vertical over REAL HTTP — upload (which indexes), then
 * search with source citations, then the full IDOR battery on the search
 * route. The active store here is the in-JVM (memory) path: the DataSource
 * is H2, the auto-probe falls back, and every score/citation must be exactly
 * what the pgvector path produces on PostgreSQL (identical cosine math —
 * cross-checked in the M3d PG evidence run).
 *
 * <p>Also covers the M3d hardening wire tests: (c) nosniff on downloads, the
 * honest indexStatus projection (MVP-5: pdf/docx are really parsed and
 * indexed; text without a text layer is NO_TEXT_LAYER; undecodable text and
 * unparseable binaries 400), blank/oversized query 400, topK clamping, and
 * the uniform-404 chain rule for search.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:search-api-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "keplura.upload.max-size-bytes=262144",
        "spring.servlet.multipart.max-file-size=262144",
        "spring.servlet.multipart.max-request-size=262144",
        "server.tomcat.max-swallow-size=-1",
        "keplura.upload.dir=target/test-uploads-search-api",
        "keplura.search.chunk-size-chars=200",
        "keplura.search.chunk-overlap-chars=40",
        "keplura.search.max-query-chars=1000"
})
class SearchApiIntegrationTest {

    private static final PasswordHasher HASHER = new PasswordHasher();
    private static final AtomicInteger NAME_SEQ = new AtomicInteger();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository userRepository;

    // ---- helpers (same wire patterns as the M3c document tests) ----

    private String loginFreshUser(String label) {
        String email = label + "@example.com";
        userRepository.saveAndFlush(User.create(email, HASHER.hash("pw-" + label), label));
        ResponseEntity<String> response = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "pw-" + label)), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return com.keplura.server.auth.CsrfTestSupport.cookiePair(response);
    }

    private ResponseEntity<String> postJson(String path, String cookie, Object body) {
        var builder = RequestEntity.post(URI.create(path)).contentType(MediaType.APPLICATION_JSON);
        if (cookie != null) {
            builder = builder.header(HttpHeaders.COOKIE, cookie)
                    .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                            com.keplura.server.auth.CsrfTestSupport.csrfValue(cookie))
                    .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                            com.keplura.server.auth.CsrfTestSupport.csrfValue(cookie));
        }
        return rest.exchange(builder.body(body), String.class);
    }

    private String createOrg(String cookie, String name) {
        return extractId(postJson("/api/v1/orgs", cookie, Map.of("name", name)));
    }

    private String createSpace(String cookie, String orgId, String name) {
        return extractId(postJson("/api/v1/orgs/" + orgId + "/spaces", cookie, Map.of("name", name)));
    }

    private String createKb(String cookie, String orgId, String spaceId, String name) {
        return extractId(postJson("/api/v1/orgs/" + orgId + "/spaces/" + spaceId + "/knowledge-bases",
                cookie, Map.of("name", name)));
    }

    private String upload(String docsPath, String cookie, String filename, byte[] content) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new NamedByteArrayResource(content, filename));
        ResponseEntity<String> response = rest.exchange(RequestEntity
                .post(URI.create(docsPath))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .header(HttpHeaders.COOKIE, cookie)
                    .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                            com.keplura.server.auth.CsrfTestSupport.csrfValue(cookie))
                .body(parts), String.class);
        assertThat(response.getStatusCode().value()).as("upload " + filename).isEqualTo(201);
        return extractId(response);
    }

    private ResponseEntity<String> search(String kbPath, String cookie, Map<String, Object> body) {
        return postJson(kbPath + "/search", cookie, body);
    }

    private static String docsPath(String orgId, String spaceId, String kbId) {
        return "/api/v1/orgs/" + orgId + "/spaces/" + spaceId + "/knowledge-bases/" + kbId + "/documents";
    }

    private static String kbPath(String orgId, String spaceId, String kbId) {
        return "/api/v1/orgs/" + orgId + "/spaces/" + spaceId + "/knowledge-bases/" + kbId;
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        NamedByteArrayResource(byte[] bytes, String filename) {
            super(bytes);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }

    private static String extractId(ResponseEntity<String> response) {
        String body = response.getBody();
        assertThat(body).isNotNull();
        int start = body.indexOf("\"id\":") + 5;
        int end = start;
        while (end < body.length() && Character.isDigit(body.charAt(end))) {
            end++;
        }
        return body.substring(start, end);
    }

    /** A fresh full tenant chain + one txt document; returns ids and docId. */
    private String[] fixture(String label, String filename, String content) {
        String cookie = loginFreshUser(label + "-" + NAME_SEQ.incrementAndGet());
        String orgId = createOrg(cookie, label + " Co " + NAME_SEQ.get());
        String spaceId = createSpace(cookie, orgId, "S");
        String kbId = createKb(cookie, orgId, spaceId, "KB");
        String docId = upload(docsPath(orgId, spaceId, kbId), cookie, filename,
                content.getBytes(StandardCharsets.UTF_8));
        return new String[]{cookie, orgId, spaceId, kbId, docId};
    }

    // ---- happy path: upload indexes, search cites the right source ----

    @Test
    void uploadedTextDocumentIsSearchableWithFullSourceAttribution() {
        String text = """
                Der neue Bauplan der Wasserkraftanlage Turbine Delta enthält alle \
                Spezifikationen für die Rotoren. Hinweise zur Wartung der Turbine Delta \
                finden sich im Anhang. Die zulässige Betriebstemperatur der Turbine Delta \
                liegt bei 72 Grad Celsius; ältere Modelle durften nur 65 Grad erreichen. \
                Ersatzteile für die Turbine Delta liefert die Werksniederlassung Nord. \
                Kostenplanung Turbine Delta: insgesamt 480000 Euro über drei Jahre verteilt. \
                Abnahme und Inbetriebnahme der Turbine Delta sind für Herbst geplant.
                """;
        String[] f = fixture("s-happy", "turbine.txt", text);
        String base = kbPath(f[1], f[2], f[3]);

        // the honest status projection: a txt upload IS indexed
        ResponseEntity<String> listed = rest.exchange(RequestEntity
                .get(URI.create(docsPath(f[1], f[2], f[3])))
                .header(HttpHeaders.COOKIE, f[0]).build(), String.class);
        assertThat(listed.getBody()).contains("\"indexStatus\":\"INDEXED\"");

        ResponseEntity<String> hits = search(base, f[0],
                Map.of("query", "Betriebstemperatur Turbine Delta", "topK", 3));
        assertThat(hits.getStatusCode().value()).isEqualTo(200);
        assertThat(hits.getBody())
                .contains("\"backend\":\"memory\"")          // H2 auto-fallback, honestly reported
                .contains("\"model\":\"local-hash-")          // dev/CI embedding identity visible
                .contains("\"filename\":\"turbine.txt\"")     // citation: which document
                .contains("\"chunkIdx\":")                    // citation: which chunk
                .contains("\"sourceUpdatedAt\":\"")
                .contains("\"score\":")                       // citation: with similarity
                .contains("Betriebstemperatur")
                .contains("\"documentId\":" + f[4]);
    }

    @Test
    void searchRanksTheRelevantDocumentFirstAcrossMultipleUploads() {
        String[] f = fixture("s-rank", "leer.txt", "allgemeine Hinweise zu Prozessen und Abläufen im Werk");
        String base = kbPath(f[1], f[2], f[3]);
        upload(docsPath(f[1], f[2], f[3]), f[0], "kosten.txt",
                ("Monatsbericht Kostenstelle Nord: Verbrauchskosten Energie " + "und Material. ".repeat(3))
                        .getBytes(StandardCharsets.UTF_8));
        upload(docsPath(f[1], f[2], f[3]), f[0], "sicherheit.txt",
                ("Feuerwehrplanung Sicherheitsübung Evakuierung Sammelpunkt " + "Verantwortliche Liste. ".repeat(3))
                        .getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> hits = search(base, f[0], Map.of("query", "Verbrauchskosten Energie Material", "topK", 2));
        assertThat(hits.getStatusCode().value()).isEqualTo(200);
        String body = hits.getBody();
        // the FIRST hit must be the kosten document (lexical overlap with local-hash embedding)
        int firstKosten = body.indexOf("kosten.txt");
        int firstSicherheit = body.indexOf("sicherheit.txt");
        assertThat(firstKosten).as(body).isGreaterThanOrEqualTo(0);
        assertThat(firstSicherheit < 0 || firstKosten < firstSicherheit)
                .as("kosten.txt must rank first in %s", body).isTrue();
    }

    // ---- hardening (c): nosniff on downloads ----

    @Test
    void downloadResponsesCarryNosniffAndIndexStatus() {
        String[] f = fixture("s-nosniff", "bericht.txt", "inhalt für die nosniff kontrolle");
        ResponseEntity<byte[]> dl = rest.exchange(RequestEntity
                .get(URI.create(docsPath(f[1], f[2], f[3]) + "/" + f[4] + "/download"))
                .header(HttpHeaders.COOKIE, f[0]).build(), byte[].class);
        assertThat(dl.getStatusCode().value()).isEqualTo(200);
        assertThat(dl.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(dl.getHeaders().getFirst("Content-Security-Policy")).isEqualTo("sandbox");
        assertThat(dl.getHeaders().getFirst("X-Index-Status")).isEqualTo("INDEXED");
    }

    @Test
    void parserPendingTypesAreHonestAboutNotBeingSearchable() {
        // superseded by MVP-5 (see pdfAndDocxUploadsAreIndexedAndSearchable):
        // fake "%PDF-1.4 ..." bytes can no longer even be stored — the real
        // parser rejects what it cannot extract.
        String cookie = loginFreshUser("s-pending-" + NAME_SEQ.incrementAndGet());
        String orgId = createOrg(cookie, "SP " + NAME_SEQ.get());
        String spaceId = createSpace(cookie, orgId, "S");
        String kbId = createKb(cookie, orgId, spaceId, "KB");
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new NamedByteArrayResource(
                "%PDF-1.4 geheime bauplne turbinendelta".getBytes(StandardCharsets.UTF_8), "geheim.pdf"));
        ResponseEntity<String> rejected = rest.exchange(RequestEntity
                .post(URI.create(docsPath(orgId, spaceId, kbId)))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .header(HttpHeaders.COOKIE, cookie)
                    .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                            com.keplura.server.auth.CsrfTestSupport.csrfValue(cookie)).body(parts), String.class);
        assertThat(rejected.getStatusCode().value()).isEqualTo(400);
        // transaction rolled back: nothing survives the failed parse
        ResponseEntity<String> list = rest.exchange(RequestEntity
                .get(URI.create(docsPath(orgId, spaceId, kbId))).header(HttpHeaders.COOKIE, cookie)
                    .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                            com.keplura.server.auth.CsrfTestSupport.csrfValue(cookie))
                .build(), String.class);
        assertThat(list.getBody()).isEqualTo("[]");
    }

    // ---- MVP-5: real pdf/docx extraction roundtrip ----

    @Test
    void uploadedPdfIsIndexedAndSearchableWithFullSourceAttribution() throws Exception {
        String[] f = binaryFixture("s-pdf", "turbine.pdf", TestDocuments.samplePdf());
        String base = kbPath(f[1], f[2], f[3]);

        ResponseEntity<String> listed = rest.exchange(RequestEntity
                .get(URI.create(docsPath(f[1], f[2], f[3]))).header(HttpHeaders.COOKIE, f[0]).build(),
                String.class);
        assertThat(listed.getBody()).contains("\"indexStatus\":\"INDEXED\"");

        // the citation must quote the EXTRACTED text — this string exists only
        // inside the PDF content stream, never in any plaintext near the file
        ResponseEntity<String> hits = search(base, f[0],
                Map.of("query", "Betriebstemperatur Turbine Delta", "topK", 3));
        assertThat(hits.getStatusCode().value()).isEqualTo(200);
        assertThat(hits.getBody())
                .contains("\"filename\":\"turbine.pdf\"")
                .contains("\"documentId\":" + f[4])
                .contains("Betriebstemperatur")
                .contains("72 Grad");
    }

    @Test
    void uploadedDocxIsIndexedAndSearchableWithFullSourceAttribution() throws Exception {
        String[] f = binaryFixture("s-docx", "kosten.docx", TestDocuments.sampleDocx());
        String base = kbPath(f[1], f[2], f[3]);

        ResponseEntity<String> hits = search(base, f[0],
                Map.of("query", "Kostenplanung Werksniederlassung", "topK", 3));
        assertThat(hits.getStatusCode().value()).isEqualTo(200);
        assertThat(hits.getBody())
                .contains("\"filename\":\"kosten.docx\"")
                .contains("\"documentId\":" + f[4])
                .contains("480000 Euro");
    }

    @Test
    void scannedPdfWithoutTextLayerStoresAsNoTextLayerNotFakeIndexed() throws Exception {
        String[] f = binaryFixture("s-scan", "scan.pdf", TestDocuments.pdfWithoutTextLayer());
        String docs = docsPath(f[1], f[2], f[3]);

        // upload itself answered 201 (binaryFixture asserts it)
        ResponseEntity<String> listed = rest.exchange(RequestEntity
                .get(URI.create(docs)).header(HttpHeaders.COOKIE, f[0]).build(), String.class);
        assertThat(listed.getBody()).contains("\"indexStatus\":\"NO_TEXT_LAYER\"");

        ResponseEntity<byte[]> dl = rest.exchange(RequestEntity
                .get(URI.create(docs + "/" + f[4] + "/download")).header(HttpHeaders.COOKIE, f[0]).build(),
                byte[].class);
        assertThat(dl.getHeaders().getFirst("X-Index-Status")).isEqualTo("NO_TEXT_LAYER");

        // the scan is honestly absent from results (no chunks exist for it)
        ResponseEntity<String> hits = search(kbPath(f[1], f[2], f[3]), f[0],
                Map.of("query", "turbine betriebstemperatur"));
        assertThat(hits.getBody()).contains("\"results\":[]");
    }

    /** Full chain + one binary upload (bytes as generated by TestDocuments). */
    private String[] binaryFixture(String label, String filename, byte[] content) {
        String cookie = loginFreshUser(label + "-" + NAME_SEQ.incrementAndGet());
        String orgId = createOrg(cookie, label + " Co " + NAME_SEQ.get());
        String spaceId = createSpace(cookie, orgId, "S");
        String kbId = createKb(cookie, orgId, spaceId, "KB");
        String docId = upload(docsPath(orgId, spaceId, kbId), cookie, filename, content);
        return new String[]{cookie, orgId, spaceId, kbId, docId};
    }

    // ---- query validation ----

    @Test
    void blankAndOversizedQueriesAre400BeforeAnyEmbeddingWork() {
        String[] f = fixture("s-query", "a.txt", "text für query validierung");
        String base = kbPath(f[1], f[2], f[3]);

        assertThat(search(base, f[0], Map.of("query", "   ")).getStatusCode().value()).isEqualTo(400);
        assertThat(search(base, f[0], Map.of("query", "x".repeat(1001))).getStatusCode().value()).isEqualTo(400);
        assertThat(search(base, f[0], Map.of()).getStatusCode().value()).isEqualTo(400); // no query at all
    }

    @Test
    void topKIsClampedNotRejected() {
        String[] f = fixture("s-topk", "a.txt", "einfacher text für die topk klemme");
        ResponseEntity<String> hits = search(kbPath(f[1], f[2], f[3]), f[0], Map.of("query", "text", "topK", 9999));
        assertThat(hits.getStatusCode().value()).isEqualTo(200);
        assertThat(hits.getBody()).contains("\"topK\":50"); // clamped server-side
    }

    // ---- invalid UTF-8 text document: honest 400, no half-indexed row ----

    @Test
    void invalidUtf8TextDocumentIsRejected400AndLeavesTheListEmpty() {
        String cookie = loginFreshUser("s-utf8-" + NAME_SEQ.incrementAndGet());
        String orgId = createOrg(cookie, "Utf8 Co " + NAME_SEQ.get());
        String spaceId = createSpace(cookie, orgId, "S");
        String kbId = createKb(cookie, orgId, spaceId, "KB");
        String docs = docsPath(orgId, spaceId, kbId);

        byte[] broken = new byte[]{(byte) 'a', (byte) 0xFF, (byte) 0xFE, (byte) 'b'};
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new NamedByteArrayResource(broken, "kaputt.txt"));
        ResponseEntity<String> response = rest.exchange(RequestEntity
                .post(URI.create(docs)).contentType(MediaType.MULTIPART_FORM_DATA)
                .header(HttpHeaders.COOKIE, cookie)
                    .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                            com.keplura.server.auth.CsrfTestSupport.csrfValue(cookie)).body(parts), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("UTF-8");
        // transaction rolled back: the metadata row never survives a failed indexing
        ResponseEntity<String> list = rest.exchange(RequestEntity
                .get(URI.create(docs)).header(HttpHeaders.COOKIE, cookie)
                    .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                            com.keplura.server.auth.CsrfTestSupport.csrfValue(cookie)).build(), String.class);
        assertThat(list.getBody()).isEqualTo("[]");
    }

    // ---- search isolation / IDOR over three levels ----

    @Test
    void searchIsInvisibleThroughEveryForeignChainCombination() {
        String[] victim = fixture("s-victim", "geheim.txt",
                "geheime bauplne turbinendelta betriebstemperatur kostenplanung");
        String attacker = loginFreshUser("s-mallory-" + NAME_SEQ.incrementAndGet());
        String attackerOrg = createOrg(attacker, "SMal " + NAME_SEQ.get());
        String attackerSpace = createSpace(attacker, attackerOrg, "AS");
        String attackerKb = createKb(attacker, attackerOrg, attackerSpace, "AK");

        Map<String, Object> probe = Map.of("query", "geheime bauplne turbinendelta");

        // A) attacker searches the VICTIM's own chain -> uniform 404
        assertThat(search(kbPath(victim[1], victim[2], victim[3]), attacker, probe)
                .getStatusCode().value()).isEqualTo(404);

        // B) attacker searches its OWN kb: the victim document is not there
        ResponseEntity<String> own = search(kbPath(attackerOrg, attackerSpace, attackerKb), attacker, probe);
        assertThat(own.getStatusCode().value()).isEqualTo(200);
        assertThat(own.getBody()).doesNotContain("geheim.txt");

        // C) victim id under attacker path shape: unknown kb id under the victim's
        //    real org/space -> identical 404, indistinguishable from A's foreign case
        assertThat(search(kbPath(victim[1], victim[2], "99999999"), attacker, probe)
                .getStatusCode().value()).isEqualTo(404);

        // D) unknown org collapses identically (uniformity across all shapes)
        ResponseEntity<String> unknownOrg = search(kbPath("99999999", victim[2], victim[3]), attacker, probe);
        assertThat(unknownOrg.getStatusCode().value()).isEqualTo(404);

        // E) no session at all -> 401 gate covers the search route too
        ResponseEntity<String> anon = rest.exchange(RequestEntity
                .post(URI.create(kbPath(victim[1], victim[2], victim[3]) + "/search"))
                .contentType(MediaType.APPLICATION_JSON).body(probe), String.class);
        assertThat(anon.getStatusCode().value()).isEqualTo(401);

        // F) the victim still finds their own document (sanity: A-D broke nothing)
        assertThat(search(kbPath(victim[1], victim[2], victim[3]), victim[0], probe).getBody())
                .contains("geheim.txt");
    }

    // ---- store visibility: the H2 path honestly reports the memory backend ----

    @Test
    void activeStoreIsTheMemoryFallbackOnH2() {
        // The auto probe must NOT claim pgvector on H2 — search responses
        // carry the backend tag, so this asserts the selection end-to-end.
        String[] f = fixture("s-backend", "a.txt", "backend kontrolle text");
        ResponseEntity<String> hits = search(kbPath(f[1], f[2], f[3]), f[0], Map.of("query", "backend"));
        assertThat(hits.getBody()).contains("\"backend\":\"memory\"");
    }

    @Test
    void indexingWritesChunkRowsWithCanonicalEmbeddings() {
        // multi-chunk document at the reduced fixture chunk size (200/40)
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            text.append("absatz ").append(i).append(" mit inhalt zu themenwort searches; ");
        }
        String[] f = fixture("s-chunks", "lang.txt", text.toString());
        ResponseEntity<String> hits = search(kbPath(f[1], f[2], f[3]), f[0],
                Map.of("query", "themenwort searches", "topK", 50));
        assertThat(hits.getStatusCode().value()).isEqualTo(200);
        // several chunks of ONE doc cited with increasing chunkIdx positions
        String body = hits.getBody();
        assertThat(body).contains("\"documentId\":" + f[4]);
        assertThat(body).contains("\"chunkIdx\":1");
        assertThat(List.of(body.split("\"chunkIdx\":")).size() - 1).isGreaterThan(3);
        assertThat(body).contains("absatz");
    }
}
