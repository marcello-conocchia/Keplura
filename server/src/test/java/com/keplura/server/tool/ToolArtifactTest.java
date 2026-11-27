package com.keplura.server.tool;

import com.keplura.server.admin.SecretCipher;
import com.keplura.server.admin.SsrfGuard;
import com.keplura.server.auth.AuthPrincipal;
import com.keplura.server.auth.AuthService;
import com.keplura.server.mcp.McpClient;
import com.keplura.server.mcp.McpToolAdapter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TU-06d: image and file results through the real {@link ToolExecutionService} on H2: allowlist by magic bytes,
 * size/count/pixel limits, owner-checked download with fixed headers, deletion with the chat, placeholders only for
 * the model. The MCP path runs against a local MCP HTTP server.
 */
class ToolArtifactTest {

    private static final JsonMapper M = JsonMapper.builder().build();
    private static final JsonNode SCHEMA = M.readTree("{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\"}},\"additionalProperties\":false}");
    private JdbcTemplate jdbc;
    private ToolExecutionService service;
    private ToolArtifactController controller;
    private final long user = 501;
    private final long other = 502;
    private long orgId;
    private long otherOrg;
    private long space;
    private long chat;
    private long otherChat;
    private final Files files = new Files();
    private HttpServer mcp;
    private final AtomicInteger mcpCalls = new AtomicInteger();
    private static final String MCP_TOKEN = "MCP_BEARER_9f3e1c7a";

    /** Returns whatever the test put into {@link #next}. */
    static final class Files implements ToolAdapter {
        volatile List<Artifact> next = List.of();
        volatile boolean internal;
        @Override public String kind() { return "files"; }
        @Override public List<ToolDefinition> definitions(Long spaceId) {
            return List.of(new ToolDefinition("files:1:get", "files", "files_get", "get", 1L, "Dateien", "liefert Dateien", SCHEMA,
                    ToolRisk.READ, true, "fp", internal));
        }
        @Override public String invoke(ToolDefinition tool, JsonNode arguments) { return "Text"; }
        @Override public Result invokeWithArtifacts(ToolContext c, ToolDefinition t, JsonNode a, Long p) { return new Result("Text", next); }
    }

    static byte[] png(int w, int h) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'});
        out.writeBytes(new byte[]{(byte) (w >>> 24), (byte) (w >>> 16), (byte) (w >>> 8), (byte) w,
                (byte) (h >>> 24), (byte) (h >>> 16), (byte) (h >>> 8), (byte) h, 8, 6, 0, 0, 0, 1, 2, 3, 4});
        return out.toByteArray();
    }

    static byte[] jpeg(int w, int h) {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0,
                (byte) 0xFF, (byte) 0xC0, 0, 17, 8, (byte) (h >> 8), (byte) h, (byte) (w >> 8), (byte) w, 3, 1, 0x22, 0};
    }

    static byte[] gif(int w, int h) {
        return new byte[]{'G', 'I', 'F', '8', '9', 'a', (byte) w, (byte) (w >> 8), (byte) h, (byte) (h >> 8), 0, 0, 0};
    }

    static byte[] webp(int w, int h) {
        int a = w - 1, b = h - 1;
        return new byte[]{'R', 'I', 'F', 'F', 22, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', 'X', 10, 0, 0, 0, 0, 0, 0, 0,
                (byte) a, (byte) (a >> 8), (byte) (a >> 16), (byte) b, (byte) (b >> 8), (byte) (b >> 16)};
    }

    static ToolAdapter.Artifact file(String name, String type, byte[] data) { return new ToolAdapter.Artifact(name, type, data); }

    static byte[] text(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @BeforeEach void setup() throws Exception {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:artifacts-" + java.util.UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        orgId = id("insert into organization (name, created_at) values ('Org', current_timestamp)");
        otherOrg = id("insert into organization (name, created_at) values ('Andere', current_timestamp)");
        for (long[] m : new long[][]{{orgId, user}, {orgId, other}, {otherOrg, user}}) {
            jdbc.update("insert into membership (org_id, user_id, role, created_at) values (?, ?, 'MEMBER', current_timestamp)", m[0], m[1]);
        }
        space = id("insert into space (org_id, name, created_at) values (" + orgId + ", 'Raum', current_timestamp)");
        chat = chat(user);
        otherChat = chat(user);
        mcp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mcp.createContext("/mcp", e -> {
            String body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (e.getRequestMethod().equals("DELETE") || !body.contains("\"id\"")) { e.sendResponseHeaders(202, -1); e.close(); return; }
            JsonNode req = M.readTree(body);
            String result;
            if (req.path("method").asString().equals("initialize")) {
                result = "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}";
            } else {
                mcpCalls.incrementAndGet();
                String png = Base64.getEncoder().encodeToString(png(2, 2));
                String pdf = Base64.getEncoder().encodeToString(text("%PDF-1.7\nSECRET_PDF_BODY"));
                result = "{\"content\":[{\"type\":\"text\",\"text\":\"Hier die Dateien\"},"
                        + "{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"" + png + "\"},"
                        + "{\"type\":\"resource\",\"resource\":{\"uri\":\"file:///x/bericht.pdf\",\"mimeType\":\"application/pdf\",\"blob\":\"" + pdf + "\"}},"
                        + "{\"type\":\"resource\",\"resource\":{\"uri\":\"file:///x/n.txt\",\"text\":\"Textressource\"}},"
                        + "{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"***kein base64***\"}]}";
            }
            byte[] out = ("{\"jsonrpc\":\"2.0\",\"id\":" + req.path("id") + ",\"result\":" + result + "}").getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "application/json");
            e.getResponseHeaders().set("Mcp-Session-Id", "s1");
            e.sendResponseHeaders(200, out.length);
            try (var o = e.getResponseBody()) { o.write(out); }
        });
        // Echoes the bearer it received into a resource URI and into file bodies (review finding 5).
        mcp.createContext("/echo", e -> {
            String body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (e.getRequestMethod().equals("DELETE") || !body.contains("\"id\"")) { e.sendResponseHeaders(202, -1); e.close(); return; }
            JsonNode req = M.readTree(body);
            String bearer = String.valueOf(e.getRequestHeaders().getFirst("Authorization")).replace("Bearer ", "");
            String result;
            if (req.path("method").asString().equals("initialize")) {
                result = "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}";
            } else {
                String leak = Base64.getEncoder().encodeToString(text("%PDF-1.7\nkey=" + bearer));
                String clean = Base64.getEncoder().encodeToString(png(2, 2));
                result = "{\"content\":[{\"type\":\"text\",\"text\":\"Echo\"},"
                        + "{\"type\":\"resource\",\"resource\":{\"uri\":\"file:///x/" + bearer + ".png\",\"mimeType\":\"image/png\",\"blob\":\"" + clean + "\"}},"
                        + "{\"type\":\"resource\",\"resource\":{\"uri\":\"file:///x/leak.pdf\",\"mimeType\":\"application/pdf\",\"blob\":\"" + leak + "\"}}]}";
            }
            byte[] out = ("{\"jsonrpc\":\"2.0\",\"id\":" + req.path("id") + ",\"result\":" + result + "}").getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "application/json");
            e.getResponseHeaders().set("Mcp-Session-Id", "s2");
            e.sendResponseHeaders(200, out.length);
            try (var o = e.getResponseBody()) { o.write(out); }
        });
        mcp.start();
        jdbc.update("""
                insert into mcp_server (name,url,enabled,tools_json,enabled_tools,tool_risks,created_at,all_spaces)
                values ('Bilder',?,true,'[{"name":"shot","inputSchema":{"type":"object"}}]','shot','{"shot":"READ"}',current_timestamp,true)""",
                "http://127.0.0.1:" + mcp.getAddress().getPort() + "/mcp");
        var cipher = new SecretCipher("artifact-test-master-key");
        jdbc.update("""
                insert into mcp_server (name,url,token_enc,enabled,tools_json,enabled_tools,tool_risks,created_at,all_spaces)
                values ('Echo',?,?,true,'[{"name":"echo","inputSchema":{"type":"object"}}]','echo','{"echo":"READ"}',current_timestamp,true)""",
                "http://127.0.0.1:" + mcp.getAddress().getPort() + "/echo", cipher.encrypt(MCP_TOKEN));
        var mcpAdapter = new McpToolAdapter(jdbc, cipher, new McpClient(new SsrfGuard("127.0.0.0/8")));
        service = new ToolExecutionService(jdbc, List.of(files, mcpAdapter), new ToolAuditLog(jdbc));
        controller = new ToolArtifactController(new ToolArtifactStore(jdbc));
    }

    @AfterEach void stop() { mcp.stop(0); }

    private long id(String sql) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(c -> c.prepareStatement(sql, new String[]{"id"}), keys);
        return keys.getKey().longValue();
    }

    private long chat(long owner) {
        return id("insert into chat_conversation (org_id, space_id, created_by, title, created_at, updated_at) values ("
                + orgId + ", " + space + ", " + owner + ", 'Chat', current_timestamp, current_timestamp)");
    }

    private long prompt(long conversation) {
        return id("insert into chat_message(conversation_id,role,content,model,citations_json,status,created_at) values("
                + conversation + ",'user','Dateien bitte','','[]','complete',current_timestamp)");
    }

    private ToolExecutionService.Outcome call(List<ToolAdapter.Artifact> artifacts, Long promptId) {
        files.next = artifacts;
        var turn = service.openTurn(service.resolveContext(user, orgId, chat), promptId);
        return turn.call("files_get", "{}");
    }

    private List<ToolArtifactStore.View> artifactsOfLastCard() {
        var cards = service.cards(user, orgId, chat);
        return cards.get(cards.size() - 1).artifacts();
    }

    private org.springframework.http.ResponseEntity<byte[]> download(long who, long org, long conversation, String id) {
        var session = new MockHttpSession();
        session.setAttribute(AuthService.SESSION_PRINCIPAL_ATTR, new AuthPrincipal(who, "P"));
        var request = new MockHttpServletRequest();
        request.setSession(session);
        return controller.download(org, conversation, id, request);
    }

    @Test void allowedTypesAreStoredShownAndTheModelOnlySeesPlaceholders() {
        var out = call(List.of(file("../../etc/bild.svg.png", "image/png", png(10, 10)), file("foto", "image/jpeg", jpeg(640, 480)),
                file("a.gif", "image/gif", gif(3, 3)), file("w.webp", "image/webp", webp(100, 50))), null);
        assertThat(out.invoked()).isTrue();
        // Names are generated by Keplura, never taken from the upstream.
        assertThat(out.text()).contains("[Datei-Ergebnis: ergebnis-1.png (image/png").contains("ergebnis-2.jpg (image/jpeg")
                .contains("ergebnis-3.gif (image/gif").contains("ergebnis-4.webp (image/webp").doesNotContain("IHDR").doesNotContain("JFIF")
                .doesNotContain("bild.svg").doesNotContain("etc");
        assertThat(artifactsOfLastCard()).extracting(ToolArtifactStore.View::mediaType)
                .containsExactly("image/png", "image/jpeg", "image/gif", "image/webp");
        assertThat(artifactsOfLastCard()).allSatisfy(v -> assertThat(v.image()).isTrue());

        var docs = call(List.of(file("bericht.pdf", "application/pdf", text("%PDF-1.4 SECRET_PDF")),
                file("liste.csv", "text/csv", text("a;b\n1;SECRET_CSV\n")), file("n.txt", "text/plain", text("SECRET_TXT")),
                file("d.json", "application/json; charset=utf-8", text("{\"k\":\"SECRET_JSON\"}"))), null);
        assertThat(docs.text()).doesNotContain("SECRET_");
        assertThat(artifactsOfLastCard()).extracting(ToolArtifactStore.View::mediaType)
                .containsExactly("application/pdf", "text/csv", "text/plain", "application/json");
        assertThat(artifactsOfLastCard()).allSatisfy(v -> assertThat(v.droppedReason()).isNull());
        assertThat(service.openTurn(service.resolveContext(user, orgId, chat)).toolsJson().toString()).doesNotContain("SECRET_");
    }

    @Test void svgHtmlWrongMagicBytesAndUnknownTypesAreDropped() {
        var out = call(List.of(file("x.svg", "image/svg+xml", text("<svg xmlns='http://www.w3.org/2000/svg'/>")),
                file("x.html", "text/html", text("<html><script>alert(1)</script></html>")),
                file("tarn.txt", "text/plain", text("  <svg onload=alert(1)>")),
                file("falsch.png", "image/png", jpeg(10, 10))), null);
        assertThat(artifactsOfLastCard()).extracting(ToolArtifactStore.View::droppedReason).containsExactly(
                "Dieser Dateityp ist nicht erlaubt.", "Dieser Dateityp ist nicht erlaubt.",
                "Markup wie HTML, SVG oder XML ist nicht erlaubt.", "Der Dateityp passt nicht zum Inhalt.");
        assertThat(out.text()).contains("[Datei verworfen:");
        call(List.of(file("prog.exe", "application/octet-stream", text("MZ\u0090binary")), file("raten", null, text("plain")),
                file("b.json", "application/json", text("{kaputt")), file("bin.txt", "text/plain", new byte[]{'a', 0, 'b'})), null);
        assertThat(artifactsOfLastCard()).allSatisfy(v -> assertThat(v.droppedReason()).isNotNull());
        assertThat(jdbc.queryForObject("select count(*) from tool_artifact where data is not null", Integer.class)).isZero();
    }

    @Test void sizeCountPixelAndPerQuestionLimitsDropAndNote() {
        byte[] big = new byte[(int) ToolArtifactStore.MAX_BYTES + 1];
        System.arraycopy(text("%PDF-"), 0, big, 0, 5);
        var five = new ArrayList<ToolAdapter.Artifact>();
        for (int i = 0; i < 5; i++) five.add(file("t" + i + ".txt", "text/plain", text("x" + i)));
        var out = call(five, null);
        assertThat(out.text()).contains("1 weitere verworfen");
        assertThat(artifactsOfLastCard()).hasSize(5).filteredOn(v -> v.droppedReason() == null).hasSize(4);

        call(List.of(file("gross.pdf", "application/pdf", big), file("riesig.png", "image/png", png(8000, 8000)),
                file("ok.png", "image/png", png(6000, 6000))), null);
        assertThat(artifactsOfLastCard()).extracting(ToolArtifactStore.View::droppedReason)
                .containsExactly("Die Datei ist größer als 5 MB.", "Das Bild ist größer als 40 Megapixel.", null);

        long question = prompt(chat);
        byte[] four = new byte[4 * 1024 * 1024];
        System.arraycopy(text("%PDF-"), 0, four, 0, 5);
        call(List.of(file("a.pdf", "application/pdf", four), file("b.pdf", "application/pdf", four)), question);
        call(List.of(file("c.pdf", "application/pdf", four)), question);
        assertThat(artifactsOfLastCard()).singleElement()
                .satisfies(v -> assertThat(v.droppedReason()).isEqualTo("Die Dateien dieser Frage überschreiten zusammen 10 MB."));
    }

    @Test void downloadIsOwnerCheckedWithFixedHeadersAndNon404OnlyForTheOwner() {
        call(List.of(file("bild.png", "image/png", png(4, 4)), file("bericht.pdf", "application/pdf", text("%PDF-1.4 x")),
                file("x.svg", "image/svg+xml", text("<svg/>"))), null);
        var views = artifactsOfLastCard();
        var image = download(user, orgId, chat, views.get(0).id());
        assertThat(image.getStatusCode().value()).isEqualTo(200);
        assertThat(image.getBody()).isEqualTo(png(4, 4));
        assertThat(image.getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(image.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(image.getHeaders().getFirst("Content-Security-Policy")).isEqualTo("sandbox; default-src 'none'");
        assertThat(image.getHeaders().getFirst("Cache-Control")).isEqualTo("private, no-store");
        assertThat(image.getHeaders().getFirst("Content-Disposition")).startsWith("inline");
        var pdf = download(user, orgId, chat, views.get(1).id());
        assertThat(pdf.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
        assertThat(pdf.getHeaders().getFirst("Content-Disposition")).startsWith("attachment").contains("ergebnis-2.pdf");
        assertThat(download(user, orgId, chat, views.get(2).id()).getStatusCode().value()).isEqualTo(404); // dropped
        assertThat(download(other, orgId, chat, views.get(0).id()).getStatusCode().value()).isEqualTo(404); // other person
        assertThat(download(user, otherOrg, chat, views.get(0).id()).getStatusCode().value()).isEqualTo(404); // other org
        assertThat(download(user, orgId, otherChat, views.get(0).id()).getStatusCode().value()).isEqualTo(404); // other chat
        assertThat(download(user, orgId, chat, "00000000-0000-0000-0000-000000000000").getStatusCode().value()).isEqualTo(404);
        jdbc.update("delete from membership where user_id=? and org_id=?", user, orgId);
        assertThat(download(user, orgId, chat, views.get(0).id()).getStatusCode().value()).isEqualTo(404); // left the org
    }

    @Test void artifactsAreDeletedWithTheChatAndInternalTargetsMarkThemInternal() {
        files.internal = true;
        call(List.of(file("intern.png", "image/png", png(1, 1))), null);
        assertThat(jdbc.queryForObject("select internal from tool_artifact", Boolean.class)).isTrue();
        service.deleteCards(chat);
        assertThat(jdbc.queryForObject("select count(*) from tool_artifact", Integer.class)).isZero();
    }

    @Test void mcpImageAndResourceBlobsBecomeArtifactsAndInvalidOnesAreNoted() {
        var turn = service.openTurn(service.resolveContext(user, orgId, chat));
        String fn = turn.offered().keySet().stream().filter(n -> n.endsWith("__shot")).findFirst().orElseThrow();
        var out = turn.call(fn, "{}");
        assertThat(out.invoked()).isTrue();
        assertThat(mcpCalls).hasValue(1);
        assertThat(out.text()).contains("Hier die Dateien").contains("Textressource").contains("ergebnis-2.pdf (application/pdf")
                .doesNotContain("bericht.pdf").doesNotContain("file:///")
                .contains("Die Datei ist nicht gültig kodiert.").doesNotContain("SECRET_PDF_BODY");
        var views = service.cards(user, orgId, chat).getFirst().artifacts();
        assertThat(views).extracting(ToolArtifactStore.View::mediaType).containsExactly("image/png", "application/pdf", null);
        assertThat(new String(download(user, orgId, chat, views.get(1).id()).getBody(), StandardCharsets.UTF_8)).contains("SECRET_PDF_BODY");
    }

    @Test void tokenEchoedIntoResourceUrisOrFileBodiesNeverReachesCardsModelStorageOrDownloads() {
        var turn = service.openTurn(service.resolveContext(user, orgId, chat));
        String fn = turn.offered().keySet().stream().filter(n -> n.endsWith("__echo")).findFirst().orElseThrow();
        var out = turn.call(fn, "{}");
        assertThat(out.invoked()).isTrue();
        assertThat(out.text()).contains("Echo").contains(ToolArtifactStore.CREDENTIAL_REASON).doesNotContain(MCP_TOKEN);
        var views = service.cards(user, orgId, chat).getLast().artifacts();
        assertThat(views).extracting(ToolArtifactStore.View::name).containsExactly("ergebnis-1.png", "ergebnis-2.bin");
        assertThat(views.get(1).droppedReason()).isEqualTo(ToolArtifactStore.CREDENTIAL_REASON);
        assertThat(new String(download(user, orgId, chat, views.get(0).id()).getBody(), StandardCharsets.ISO_8859_1)).doesNotContain(MCP_TOKEN);
        assertThat(download(user, orgId, chat, views.get(1).id()).getStatusCode().value()).isEqualTo(404);
        String db = String.join("\n", jdbc.queryForList("SCRIPT", String.class));
        assertThat(db).doesNotContain(MCP_TOKEN);
        assertThat(jdbc.queryForObject("select count(*) from tool_artifact where data is not null and name like '%MCP_BEARER%'", Integer.class)).isZero();
        assertThat(M.writeValueAsString(service.cards(user, orgId, chat))).doesNotContain(MCP_TOKEN);
    }
}
