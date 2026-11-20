package com.keplura.server.knowledge;

import com.keplura.server.identity.PasswordHasher;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3c: the document vertical over REAL HTTP. Uploads go through real
 * multipart framing (RestTemplate's form converter), downloads come back as
 * raw bytes — so the sha256 the API reports is compared against bytes that
 * actually crossed the wire, and the storage directory is a real temp tree
 * under target/ (path layout and containment are inspectable).
 *
 * <p>Proves: 401 gate; whitelist 400; BOTH size limits (app-declared → 413
 * and container-framing → 413, split via mismatched 5MB/1MB config); upload →
 * list → download byte-roundtrip with matching sha256; IDOR uniformity over
 * all three tenant levels for list AND download; hostile multipart filenames
 * (traversal / encoded / unicode) sanitised to safe display names and NEVER
 * reflected in storage layout; storedPath absent from every response body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:doc-api-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // deliberately SPLIT limits so both rejection paths are wire-tested:
        // Tomcat accepts up to 5MB, the app policy caps at 1MB -> a 2MB upload
        // proves the app-level 413, a 6MB upload proves the container-level 413.
        "spring.servlet.multipart.max-file-size=5MB",
        "spring.servlet.multipart.max-request-size=5MB",
        // matches production wiring: the swallow budget must cover the whole
        // rejected body or Tomcat resets instead of answering the 413
        // (CI-observed race on Linux/Windows runners)
        "server.tomcat.max-swallow-size=-1",
        "keplura.upload.max-size-bytes=1048576",
        "keplura.upload.dir=target/test-uploads-doc-api"
})
class DocumentApiIntegrationTest {

    private static final PasswordHasher HASHER = new PasswordHasher();
    private static final AtomicInteger NAME_SEQ = new AtomicInteger();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository userRepository;

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

    private String createOrg(String cookie, String name) {
        ResponseEntity<String> created = postJson("/api/v1/orgs", cookie, Map.of("name", name));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        return extractId(created);
    }

    private String createSpace(String cookie, String orgId, String name) {
        ResponseEntity<String> created = postJson("/api/v1/orgs/" + orgId + "/spaces", cookie,
                Map.of("name", name));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        return extractId(created);
    }

    private String createKb(String cookie, String orgId, String spaceId, String name) {
        ResponseEntity<String> created = postJson(
                "/api/v1/orgs/" + orgId + "/spaces/" + spaceId + "/knowledge-bases", cookie,
                Map.of("name", name));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        return extractId(created);
    }

    private ResponseEntity<String> postJson(String path, String cookie, Map<String, Object> body) {
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

    /** Real multipart POST of one "file" part with the given client filename (docsPath = full collection URL). */
    private ResponseEntity<String> uploadMultipart(String docsCollectionPath, String cookie,
                                                   String partFilename, byte[] content,
                                                   String partContentType) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new NamedByteArrayResource(content, partFilename));
        var builder = RequestEntity
                .post(URI.create(docsCollectionPath))
                .contentType(MediaType.MULTIPART_FORM_DATA);
        if (cookie != null) {
            builder = builder.header(HttpHeaders.COOKIE, cookie)
                    .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                            com.keplura.server.auth.CsrfTestSupport.csrfValue(cookie))
                    .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                            com.keplura.server.auth.CsrfTestSupport.csrfValue(cookie));
        }
        return rest.exchange(builder.body(parts), String.class);
    }

    private ResponseEntity<byte[]> download(String path, String cookie) {
        var builder = RequestEntity.get(URI.create(path));
        if (cookie != null) {
            builder = builder.header(HttpHeaders.COOKIE, cookie)
                    .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                            com.keplura.server.auth.CsrfTestSupport.csrfValue(cookie))
                    .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                            com.keplura.server.auth.CsrfTestSupport.csrfValue(cookie));
        }
        return rest.exchange(builder.build(), byte[].class);
    }

    private static String docsPath(String orgId, String spaceId, String kbId) {
        return "/api/v1/orgs/" + orgId + "/spaces/" + spaceId + "/knowledge-bases/" + kbId + "/documents";
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** ByteArrayResource that reports a CLIENT-CHOSEN filename in the multipart part. */
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

    // ---- session gate ----

    @Test
    void documentEndpointsRequireSession() {
        String base = docsPath("1", "1", "1");
        ResponseEntity<String> list = rest.exchange(RequestEntity.get(URI.create(base)).build(),
                String.class);
        assertThat(list.getStatusCode().value()).isEqualTo(401);

        // multipart POST without a session must 401 BEFORE the body is parsed
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new NamedByteArrayResource("hi".getBytes(StandardCharsets.UTF_8), "a.txt"));
        ResponseEntity<String> upload = rest.exchange(RequestEntity
                .post(URI.create(base))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts), String.class);
        assertThat(upload.getStatusCode().value()).isEqualTo(401);

        ResponseEntity<byte[]> dl = rest.exchange(RequestEntity
                .get(URI.create(base + "/1/download")).build(), byte[].class);
        assertThat(dl.getStatusCode().value()).isEqualTo(401);
    }

    // ---- upload happy path + download roundtrip (AC2 semantics over the wire) ----

    @Test
    void officeUploadPersistsSourceSectionsAndPhysicalSlideLocations() throws Exception {
        String cookie = loginFreshUser("office-" + NAME_SEQ.incrementAndGet());
        String org = createOrg(cookie, "Office" + NAME_SEQ.get());
        String space = createSpace(cookie, org, "Dateien");
        String kb = createKb(cookie, org, space, "Officewissen");
        String base = docsPath(org, space, kb);
        var xlsx = uploadMultipart(base, cookie, "budget.xlsx",
                com.keplura.server.search.TestDocuments.sampleXlsx(), "text/plain");
        assertThat(xlsx.getStatusCode().value()).isEqualTo(201);
        assertThat(xlsx.getBody()).contains("INDEXED", "spreadsheetml.sheet");
        var sheet = download(base + "/" + extractId(xlsx) + "/preview?chunkIdx=0", cookie);
        assertThat(sheet.getStatusCode().value()).isEqualTo(200);
        assertThat(new String(sheet.getBody(), StandardCharsets.UTF_8))
                .contains("Kostenplanung", "Prueffrist", "53", "\"pageNumber\":null");
        var pptx = uploadMultipart(base, cookie, "vortrag.pptx",
                com.keplura.server.search.TestDocuments.samplePptx(), "text/plain");
        assertThat(pptx.getStatusCode().value()).isEqualTo(201);
        assertThat(pptx.getBody()).contains("INDEXED", "presentationml.presentation");
        // Empty physical slides still occupy their source positions.
        var preview = download(base + "/" + extractId(pptx) + "/preview?chunkIdx=2&pageNumber=3", cookie);
        assertThat(preview.getStatusCode().value()).isEqualTo(200);
        assertThat(new String(preview.getBody(), StandardCharsets.UTF_8))
                .contains("Bauplaene", "Sprechernotiz", "\"pageNumber\":3", "Folie 3");
        assertThat(download(base + "/" + extractId(pptx) + "/preview?chunkIdx=2&pageNumber=2", cookie)
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void sourcePreviewReturnsBoundedPlainTextWithVerifiedPositionAndPrivateHeaders() {
        String cookie=loginFreshUser("preview-"+NAME_SEQ.incrementAndGet());
        String org=createOrg(cookie,"Vorschau"+NAME_SEQ.get());
        String space=createSpace(cookie,org,"Betrieb");
        String kb=createKb(cookie,org,space,"Handbuch");
        String base=docsPath(org,space,kb);
        String text="# Leitwarte\n<script>alert('keine-ausfuehrung')</script>\n42 Prozent.";
        String doc=extractId(uploadMultipart(base,cookie,"handbuch.md",text.getBytes(StandardCharsets.UTF_8),"text/plain"));
        var response=download(base+"/"+doc+"/preview?chunkIdx=0&startOffset=0",cookie);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        String body=new String(response.getBody(),StandardCharsets.UTF_8);
        assertThat(body).contains("\"sourceUpdatedAt\":\"", "42 Prozent", "<script>", "\"exactLocation\":true", "\"sectionTitle\":\"Leitwarte\"")
                .doesNotContain("storedPath", "test-uploads", "orgs/");
        assertThat(download(base+"/"+doc+"/preview?chunkIdx=0&startOffset=99999",cookie).getStatusCode().value()).isEqualTo(404);
        assertThat(download(base+"/"+doc+"/preview?chunkIdx=-1",cookie).getStatusCode().value()).isEqualTo(400);
        assertThat(download(base+"/"+doc+"/preview?chunkIdx=99999",cookie).getStatusCode().value()).isEqualTo(404);

        String large=("Absatz "+"x".repeat(750)+"\n\n").repeat(60);
        String big=extractId(uploadMultipart(base,cookie,"lang.txt",large.getBytes(StandardCharsets.UTF_8),"text/plain"));
        var bounded=download(base+"/"+big+"/preview?chunkIdx=10",cookie);
        assertThat(bounded.getStatusCode().value()).isEqualTo(200);
        var json=tools.jackson.databind.json.JsonMapper.builder().build().readTree(bounded.getBody());
        assertThat(json.path("blocks").size()).isLessThanOrEqualTo(21);
        assertThat(json.path("hasNext").booleanValue()).isTrue();
    }

    @Test
    void previewRequiresSessionAndRejectsForeignTenantCoordinatesWithoutText() {
        String owner=loginFreshUser("preview-owner-"+NAME_SEQ.incrementAndGet());
        String other=loginFreshUser("preview-other-"+NAME_SEQ.incrementAndGet());
        String org=createOrg(owner,"Eigene Dokumente"+NAME_SEQ.get());
        String space=createSpace(owner,org,"Betrieb");
        String kb=createKb(owner,org,space,"Geheim");
        String ownOrg=createOrg(other,"Andere Organisation"+NAME_SEQ.get());
        String ownSpace=createSpace(other,ownOrg,"Anderer Bereich");
        String ownKb=createKb(other,ownOrg,ownSpace,"Andere Dokumente");
        String doc=extractId(uploadMultipart(docsPath(org,space,kb),owner,"geheim.txt",
                "NUR_BERECHTIGTE_SEHEN_DIESE_QUELLE".getBytes(StandardCharsets.UTF_8),"text/plain"));
        String original=docsPath(org,space,kb)+"/"+doc+"/preview";
        assertThat(download(original,null).getStatusCode().value()).isEqualTo(401);
        for (String path:List.of(original, docsPath(ownOrg,ownSpace,ownKb)+"/"+doc+"/preview",
                docsPath(org,ownSpace,kb)+"/"+doc+"/preview", docsPath(org,space,ownKb)+"/"+doc+"/preview")) {
            var rejected=download(path,other);
            assertThat(rejected.getStatusCode().value()).isEqualTo(404);
            assertThat(rejected.getBody()==null ? "" : new String(rejected.getBody(),StandardCharsets.UTF_8))
                    .doesNotContain("NUR_BERECHTIGTE", "geheim.txt");
        }
    }

    @Test
    void blankDocumentPreviewNeverClaimsToHaveReadableSourceText() {
        String cookie=loginFreshUser("preview-blank-"+NAME_SEQ.incrementAndGet());
        String org=createOrg(cookie,"Leere Vorschau"+NAME_SEQ.get());
        String space=createSpace(cookie,org,"Betrieb");
        String kb=createKb(cookie,org,space,"Leer");
        String base=docsPath(org,space,kb);
        String doc=extractId(uploadMultipart(base,cookie,"leer.txt","   ".getBytes(StandardCharsets.UTF_8),"text/plain"));
        var response=download(base+"/"+doc+"/preview",cookie);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(new String(response.getBody(),StandardCharsets.UTF_8))
                .contains("\"indexStatus\":\"NOT_INDEXED\"", "\"blocks\":[]", "\"exactLocation\":false");
    }

    @Test
    void uploadListAndDownloadRoundtripWithMatchingSha256() {
        String tag = "d-round-" + NAME_SEQ.incrementAndGet();
        String cookie = loginFreshUser(tag);
        String orgId = createOrg(cookie, "Roundtrip Co " + NAME_SEQ.get());
        String spaceId = createSpace(cookie, orgId, "R-Raum");
        String kbId = createKb(cookie, orgId, spaceId, "R-KB");

        byte[] payload = "Dokument mit Umlauten: äöü ß € — roundtrip-Test.".getBytes(StandardCharsets.UTF_8);
        ResponseEntity<String> uploaded = uploadMultipart(docsPath(orgId, spaceId, kbId), cookie,
                "bericht.txt", payload, "text/plain");
        assertThat(uploaded.getStatusCode().value()).isEqualTo(201);
        String docId = extractId(uploaded);
        String expectedSha = sha256Hex(payload);

        assertThat(uploaded.getBody())
                .contains("\"filename\":\"bericht.txt\"")
                .contains("\"sha256\":\"" + expectedSha + "\"")
                .contains("\"sizeBytes\":" + payload.length)
                .contains("\"contentType\":\"text/plain; charset=utf-8\"")
                // the DB path convention is server-internal — must never leak
                .doesNotContain("storedPath")
                .doesNotContain("orgs/");

        // list shows the document
        ResponseEntity<String> list = rest.exchange(RequestEntity
                .get(URI.create(docsPath(orgId, spaceId, kbId)))
                .header(HttpHeaders.COOKIE, cookie)
                    .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                            com.keplura.server.auth.CsrfTestSupport.csrfValue(cookie)).build(), String.class);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getBody()).contains("bericht.txt").contains(expectedSha);

        // download: bytes identical, sha256 identical, server-mapped type + attachment disposition
        ResponseEntity<byte[]> dl = download(docsPath(orgId, spaceId, kbId), cookie, docId);
        assertThat(dl.getStatusCode().value()).isEqualTo(200);
        assertThat(dl.getBody()).isEqualTo(payload);
        assertThat(sha256Hex(dl.getBody())).isEqualTo(expectedSha);
        assertThat(dl.getHeaders().getContentType())
                .isEqualTo(MediaType.parseMediaType("text/plain; charset=utf-8"));
        assertThat(dl.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"bericht.txt\"");
        assertThat(dl.getHeaders().getContentLength()).isEqualTo(payload.length);
    }

    private ResponseEntity<byte[]> download(String base, String cookie, String docId) {
        return download(base + "/" + docId + "/download", cookie);
    }

    @Test
    void whitelistedTypesRoundtripAndUnknownTypesReject400() throws Exception {
        String cookie = loginFreshUser("d-types-" + NAME_SEQ.incrementAndGet());
        String orgId = createOrg(cookie, "Types Co " + NAME_SEQ.get());
        String spaceId = createSpace(cookie, orgId, "T");
        String kbId = createKb(cookie, orgId, spaceId, "T-KB");
        String base = docsPath(orgId, spaceId, kbId);

        byte[] body = "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8);
        // txt/md/csv stay pure-text: whitelist check only, no parser coupling
        for (String ok : List.of("b.txt", "c.md", "e.csv")) {
            assertThat(uploadMultipart(base, cookie, ok, body, "application/octet-stream")
                    .getStatusCode().value()).as(ok).isEqualTo(201);
        }
        // MVP-5: pdf/docx are PARSED at upload — a whitelist roundtrip with
        // these types needs REAL binaries (self-generated fixtures, package
        // com.keplura.server.search.TestDocuments) since fake magic-byte
        // residue is now honestly rejected 400 instead of stored.
        byte[] realPdf = com.keplura.server.search.TestDocuments.samplePdf();
        byte[] realDocx = com.keplura.server.search.TestDocuments.sampleDocx();
        assertThat(uploadMultipart(base, cookie, "a.pdf", realPdf, "application/octet-stream")
                .getStatusCode().value()).isEqualTo(201);
        assertThat(uploadMultipart(base, cookie, "OBER.PDF", realPdf, "application/octet-stream")
                .getStatusCode().value()).isEqualTo(201);
        assertThat(uploadMultipart(base, cookie, "d.docx", realDocx, "application/octet-stream")
                .getStatusCode().value()).isEqualTo(201);
        // the served type follows the EXTENSION, not the client's part header
        ResponseEntity<String> pdf = uploadMultipart(base, cookie, "x.pdf", realPdf, "text/plain");
        assertThat(pdf.getStatusCode().value()).isEqualTo(201);
        assertThat(pdf.getBody()).contains("\"contentType\":\"application/pdf\"");

        for (String bad : List.of("evil.exe", "script.sh", "page.html", "noext", "report.pdf.exe")) {
            ResponseEntity<String> rejected = uploadMultipart(base, cookie, bad, body,
                    "application/pdf"); // client CLAIMS pdf — extension wins
            assertThat(rejected.getStatusCode().value()).as(bad).isEqualTo(400);
            assertThat(rejected.getBody()).contains("\"error\"");
        }

        // MVP-5: a fake-magic .pdf (whitelisted extension, junk bytes) is now
        // REJECTED by the real parser — the honest 400 replaced "store it and
        // mark PARSER_PENDING" (M3d behaviour).
        ResponseEntity<String> fakePdf = uploadMultipart(base, cookie, "fake.pdf", body,
                "application/pdf");
        assertThat(fakePdf.getStatusCode().value()).isEqualTo(400);
        assertThat(fakePdf.getBody()).contains("could not be parsed");
    }

    // ---- size limits: app-declared AND container-framing, both → 413 ----

    @Test
    void declaredSizeOverAppLimitIs413BeforeAnyByteIsStored() {
        String cookie = loginFreshUser("d-limit-" + NAME_SEQ.incrementAndGet());
        String orgId = createOrg(cookie, "Limit Co " + NAME_SEQ.get());
        String spaceId = createSpace(cookie, orgId, "L");
        String kbId = createKb(cookie, orgId, spaceId, "L-KB");

        byte[] twoMeg = new byte[2 * 1024 * 1024]; // > app limit (1MB), < container (5MB)
        ResponseEntity<String> response = uploadMultipart(docsPath(orgId, spaceId, kbId), cookie,
                "big.txt", twoMeg, "text/plain");
        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody()).contains("size limit");
    }

    @Test
    void containerFramingLimitIsAlso413NotA500OrConnectionError() {
        String cookie = loginFreshUser("d-hard-" + NAME_SEQ.incrementAndGet());
        String orgId = createOrg(cookie, "Hard Co " + NAME_SEQ.get());
        String spaceId = createSpace(cookie, orgId, "H");
        String kbId = createKb(cookie, orgId, spaceId, "H-KB");

        byte[] sixMeg = new byte[6 * 1024 * 1024]; // > container 5MB limit
        ResponseEntity<String> response = uploadMultipart(docsPath(orgId, spaceId, kbId), cookie,
                "huge.txt", sixMeg, "text/plain");
        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody()).contains("\"error\"");
    }

    @Test
    void emptyFileIsRejected400() {
        String cookie = loginFreshUser("d-empty-" + NAME_SEQ.incrementAndGet());
        String orgId = createOrg(cookie, "Empty Co " + NAME_SEQ.get());
        String spaceId = createSpace(cookie, orgId, "E");
        String kbId = createKb(cookie, orgId, spaceId, "E-KB");

        ResponseEntity<String> response = uploadMultipart(docsPath(orgId, spaceId, kbId), cookie,
                "null.txt", new byte[0], "text/plain");
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    // ---- hostile filenames (AC3 over the wire) ----

    @Test
    void traversalFilenamesNeverShapeTheStoredPathButRoundtripSafely() {
        String cookie = loginFreshUser("d-hostile-" + NAME_SEQ.incrementAndGet());
        String orgId = createOrg(cookie, "Hostile Co " + NAME_SEQ.get());
        String spaceId = createSpace(cookie, orgId, "X");
        String kbId = createKb(cookie, orgId, spaceId, "X-KB");
        String base = docsPath(orgId, spaceId, kbId);

        byte[] payload = "harmlos".getBytes(StandardCharsets.UTF_8);

        // all-dot residue: nothing left but dots -> REJECTED 400, nothing stored
        assertThat(uploadMultipart(base, cookie, "../../....", payload, "text/plain")
                .getStatusCode().value()).isEqualTo(400);

        // filename -> expected safe display name (after last-separator cut + character whitelist)
        Map<String, String> cases = Map.of(
                "../../etc/passwd.txt", "passwd.txt",        // plain traversal
                "..%2F..%2Fevil.txt", "..2F..2Fevil.txt",    // encoded traversal: %2F is not a
                // separator to Path handling... percent dropped, dots are LITERAL characters of
                // one harmless component (never a path segment)
                "\\Windows\\System32\\x.txt", "x.txt",       // windows fake path
                "sneu\u0000geheim.txt", "sneugeheim.txt");   // NUL byte dropped

        for (Map.Entry<String, String> entry : cases.entrySet()) {
            ResponseEntity<String> uploaded = uploadMultipart(base, cookie, entry.getKey(), payload,
                    "text/plain");
            assertThat(uploaded.getStatusCode().value()).as(entry.getKey()).isEqualTo(201);
            // the display name is exactly the ONE safe component the sanitiser allows...
            assertThat(uploaded.getBody()).contains("\"filename\":\"" + entry.getValue() + "\"");
            // ...no separator ever survives into it, and the WHOLE response carries no
            // storage layout (the traversal strings never reach the filesystem path,
            // which is server-composed UUID only)
            assertThat(uploaded.getBody()).doesNotContain("\\");
            assertThat(uploaded.getBody()).doesNotContain("storedPath").doesNotContain("orgs/");
            // the bytes are reachable ONLY through the tenant path and roundtrip identical
            String docId = extractId(uploaded);
            ResponseEntity<byte[]> dl = download(base, cookie, docId);
            assertThat(dl.getStatusCode().value()).isEqualTo(200);
            assertThat(dl.getBody()).isEqualTo(payload);
        }
    }

    // ---- IDOR over three levels (list + download) ----

    @Test
    void victimDocumentsAreInvisibleThroughEveryForeignPathCombination() {
        String victim = loginFreshUser("d-victim-" + NAME_SEQ.incrementAndGet());
        String attacker = loginFreshUser("d-mallory-" + NAME_SEQ.incrementAndGet());
        String victimOrg = createOrg(victim, "DVictor " + NAME_SEQ.get());
        String attackerOrg = createOrg(attacker, "DMallory " + NAME_SEQ.get());
        String victimSpace = createSpace(victim, victimOrg, "VV");
        String attackerSpace = createSpace(attacker, attackerOrg, "AA");
        String victimKb = createKb(victim, victimOrg, victimSpace, "V-KB");
        String attackerKb = createKb(attacker, attackerOrg, attackerSpace, "A-KB");

        byte[] secret = "geheime baupläne".getBytes(StandardCharsets.UTF_8);
        ResponseEntity<String> upload = uploadMultipart(docsPath(victimOrg, victimSpace, victimKb),
                victim, "geheim.txt", secret, "text/plain");
        assertThat(upload.getStatusCode().value()).isEqualTo(201);
        String secretDocId = extractId(upload);

        // A) attacker lists the victim's full chain -> uniform 404
        ResponseEntity<String> foreignList = rest.exchange(RequestEntity
                .get(URI.create(docsPath(victimOrg, victimSpace, victimKb)))
                .header(HttpHeaders.COOKIE, attacker).build(), String.class);
        assertThat(foreignList.getStatusCode().value()).isEqualTo(404);

        // B) victim's doc id through the attacker's OWN org/space/kb -> identical 404
        ResponseEntity<byte[]> crossChain = download(
                docsPath(attackerOrg, attackerSpace, attackerKb), attacker, secretDocId);
        assertThat(crossChain.getStatusCode().value()).isEqualTo(404);
        byte[] crossBody = crossChain.getBody() == null ? new byte[0] : crossChain.getBody();
        assertThat(new String(crossBody, StandardCharsets.UTF_8)).doesNotContain("geheim");

        // C) right org+space, wrong KB (attacker guesses kb id under victim chain) -> 404,
        //    indistinguishable from a never-existing document id
        ResponseEntity<byte[]> wrongKb = download(
                docsPath(victimOrg, victimSpace, "99999999"), attacker, secretDocId);
        assertThat(wrongKb.getStatusCode().value()).isEqualTo(404);
        assertThat(wrongKb.getBody() == null ? new byte[0] : wrongKb.getBody()).isEqualTo(crossBody);

        // D) unknown org / unknown space also collapse into the identical shape
        ResponseEntity<byte[]> unknownOrg = download(
                docsPath("99999999", victimSpace, victimKb), attacker, secretDocId);
        assertThat(unknownOrg.getStatusCode().value()).isEqualTo(404);
        assertThat(unknownOrg.getBody() == null ? new byte[0] : unknownOrg.getBody()).isEqualTo(crossBody);
    }

    @Test
    void uploadIntoForeignChainIsUniform404AndLeavesNoTraceInVictimList() {
        String victim = loginFreshUser("d-v2-" + NAME_SEQ.incrementAndGet());
        String attacker = loginFreshUser("d-m2-" + NAME_SEQ.incrementAndGet());
        String victimOrg = createOrg(victim, "DVic2 " + NAME_SEQ.get());
        String attackerOrg = createOrg(attacker, "DMal2 " + NAME_SEQ.get());
        String victimSpace = createSpace(victim, victimOrg, "VS2");
        String victimKb = createKb(victim, victimOrg, victimSpace, "VK2");
        String attackerSpace = createSpace(attacker, attackerOrg, "AS2");
        String attackerKb = createKb(attacker, attackerOrg, attackerSpace, "AK2");

        byte[] junk = "injection attempt".getBytes(StandardCharsets.UTF_8);
        // attacker uploads THROUGH THE VICTIM's chain (not a member) -> 404
        ResponseEntity<String> into = uploadMultipart(docsPath(victimOrg, victimSpace, victimKb),
                attacker, "junk.txt", junk, "text/plain");
        assertThat(into.getStatusCode().value()).isEqualTo(404);

        // ...and the victim's list is untouched (still empty)
        ResponseEntity<String> victimList = rest.exchange(RequestEntity
                .get(URI.create(docsPath(victimOrg, victimSpace, victimKb)))
                .header(HttpHeaders.COOKIE, victim).build(), String.class);
        assertThat(victimList.getStatusCode().value()).isEqualTo(200);
        assertThat(victimList.getBody()).isEqualTo("[]");

        // attacker's own kb list is also empty — nothing leaked sideways
        ResponseEntity<String> attackerList = rest.exchange(RequestEntity
                .get(URI.create(docsPath(attackerOrg, attackerSpace, attackerKb)))
                .header(HttpHeaders.COOKIE, attacker).build(), String.class);
        assertThat(attackerList.getStatusCode().value()).isEqualTo(200);
        assertThat(attackerList.getBody()).isEqualTo("[]");
    }

    // ---- malformed multipart ----

    @Test
    void missingFilePartIsUniform400() {
        String cookie = loginFreshUser("d-malformed-" + NAME_SEQ.incrementAndGet());
        String orgId = createOrg(cookie, "Malformed Co " + NAME_SEQ.get());
        String spaceId = createSpace(cookie, orgId, "M");
        String kbId = createKb(cookie, orgId, spaceId, "M-KB");

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("nottheFile", new NamedByteArrayResource(new byte[]{1}, "x.txt"));
        ResponseEntity<String> response = rest.exchange(RequestEntity
                .post(URI.create(docsPath(orgId, spaceId, kbId)))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .header(HttpHeaders.COOKIE, cookie)
                    .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                            com.keplura.server.auth.CsrfTestSupport.csrfValue(cookie))
                .body(parts), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("\"error\"");
    }

    /** Pulls the "id":N value out of a created-resource response body. */
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
}
