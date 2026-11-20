package com.keplura.server.datasource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * DS-4 (ADR-0012, PO 14.11.2026) — SharePoint Online über Microsoft Graph, nur lesend.
 *
 * <p>Anmeldung als App (Client-Credentials) mit der Berechtigung {@code Sites.Selected}:
 * Keplura sieht nur Sites, die der Kunden-Admin ausdrücklich freigegeben hat.
 *
 * <p>Rechte je Datei aus {@code /permissions}:
 * Benutzer/Entra-Gruppen → {@code entra:<id>}; SharePoint-Gruppen „Mitglieder“/„Besitzer“ →
 * verbundene Microsoft-365-Gruppe der Site ({@code entra:<gruppe>}), alle anderen
 * SharePoint-Gruppen (z. B. „Besucher“) werden NICHT übernommen (fail-closed);
 * Freigabelinks „Personen in der Organisation“/„Jeder“ → {@code entra-tenant:<tid>}
 * (nur angemeldete Personen des eigenen Tenants in Keplura).
 */
public class SharePointSourceConnector implements SourceConnector {

    private static final String GRAPH = "https://graph.microsoft.com/v1.0";
    private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{1,98}");
    private static final Pattern CLIENT = Pattern.compile("[0-9a-fA-F-]{36}");
    private static final Pattern OWNER_GROUP_NAME = Pattern.compile(
            "(?i).*\\b(members|owners|mitglieder|besitzer)$");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER) // download URLs are fetched without the bearer
            .build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final String tenant;
    private final String clientId;
    private final String secret;
    private String token;
    private Instant tokenExpires = Instant.EPOCH;
    private String tenantGuid;

    private final String driveId;
    private final String rootItemId;
    private final String ownerGroupId;
    private final Map<String, String> itemIds = new HashMap<>();

    /** siteUrl: https://firma.sharepoint.com/sites/Name[/Bibliothek[/Ordner…]] */
    public SharePointSourceConnector(String siteUrl, String tenant, String clientId, String secret) throws IOException {
        if (tenant == null || !TENANT.matcher(tenant.trim()).matches()) {
            throw new IOException("Bitte die Verzeichnis-ID (Tenant) der App-Registrierung angeben.");
        }
        if (clientId == null || !CLIENT.matcher(clientId.trim()).matches()) {
            throw new IOException("Bitte die Anwendungs-ID (Client-ID) der App-Registrierung angeben.");
        }
        if (secret == null || secret.isEmpty()) {
            throw new IOException("Bitte den geheimen Clientschlüssel der App-Registrierung angeben.");
        }
        this.tenant = tenant.trim();
        this.clientId = clientId.trim();
        this.secret = secret;

        URI u;
        try {
            u = URI.create(siteUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("Die Site-Adresse ist ungültig.");
        }
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(u.getScheme()) || !host.endsWith(".sharepoint.com") || u.getUserInfo() != null) {
            throw new IOException("Bitte eine Adresse wie https://firma.sharepoint.com/sites/Vertrieb angeben.");
        }
        String[] seg = decode(u.getRawPath()).replaceAll("^/+|/+$", "").split("/");
        if (seg.length < 2 || !(seg[0].equalsIgnoreCase("sites") || seg[0].equalsIgnoreCase("teams"))) {
            throw new IOException("Die Adresse muss /sites/… oder /teams/… enthalten.");
        }
        JsonNode site = get(GRAPH + "/sites/" + host + ":/" + seg[0] + "/" + enc(seg[1]) + "?$select=id");
        String siteId = site.path("id").asString("");
        String library = seg.length > 2 ? seg[2] : null;
        String folder = seg.length > 3 ? String.join("/", java.util.Arrays.copyOfRange(seg, 3, seg.length)) : null;

        JsonNode drive = null;
        if (library != null) {
            for (JsonNode d : get(GRAPH + "/sites/" + siteId + "/drives?$select=id,name,webUrl,owner").path("value")) {
                String web = decode(d.path("webUrl").asString(""));
                if (d.path("name").asString("").equalsIgnoreCase(library) || web.toLowerCase(Locale.ROOT)
                        .endsWith("/" + library.toLowerCase(Locale.ROOT))) {
                    drive = d;
                    break;
                }
            }
            if (drive == null) {
                // "Freigegebene Dokumente"/"Shared Documents" may also be written as the default library
                drive = get(GRAPH + "/sites/" + siteId + "/drive?$select=id,name,webUrl,owner");
                if (!drive.path("name").asString("").equalsIgnoreCase(library)
                        && !decode(drive.path("webUrl").asString("")).toLowerCase(Locale.ROOT)
                        .endsWith("/" + library.toLowerCase(Locale.ROOT))) {
                    folder = folder == null ? library : library + "/" + folder;
                }
            }
        } else {
            drive = get(GRAPH + "/sites/" + siteId + "/drive?$select=id,name,webUrl,owner");
        }
        this.driveId = drive.path("id").asString("");
        String group = drive.path("owner").path("group").path("id").asString(null);
        this.ownerGroupId = group == null || group.isBlank() ? null : group.toLowerCase(Locale.ROOT);
        JsonNode root = folder == null
                ? get(GRAPH + "/drives/" + driveId + "/root?$select=id,folder")
                : get(GRAPH + "/drives/" + driveId + "/root:/" + encPath(folder) + "?$select=id,folder");
        if (root.path("folder").isMissingNode()) {
            throw new IOException("Der angegebene Ordner wurde in der Bibliothek nicht gefunden.");
        }
        this.rootItemId = root.path("id").asString("");
    }

    @Override
    public List<Entry> list(int limit) throws IOException {
        List<Entry> out = new ArrayList<>();
        Deque<String[]> dirs = new ArrayDeque<>();
        dirs.add(new String[]{rootItemId, ""});
        while (!dirs.isEmpty() && out.size() < limit) {
            String[] dir = dirs.poll();
            String url = GRAPH + "/drives/" + driveId + "/items/" + dir[0]
                    + "/children?$select=id,name,size,lastModifiedDateTime,file,folder&$top=200";
            while (url != null && out.size() < limit) {
                JsonNode page = get(url);
                for (JsonNode item : page.path("value")) {
                    String name = item.path("name").asString("");
                    String rel = dir[1].isEmpty() ? name : dir[1] + "/" + name;
                    if (!item.path("folder").isMissingNode()) {
                        dirs.add(new String[]{item.path("id").asString(), rel});
                    } else if (!item.path("file").isMissingNode()) {
                        itemIds.put(rel, item.path("id").asString());
                        out.add(new Entry(rel, item.path("size").asLong(0),
                                Instant.parse(item.path("lastModifiedDateTime").asString("1970-01-01T00:00:00Z"))));
                        if (out.size() >= limit) break;
                    }
                }
                String next = page.path("@odata.nextLink").asString(null);
                url = next != null && next.startsWith(GRAPH + "/") ? next : null;
            }
        }
        return out;
    }

    @Override
    public InputStream open(String relativePath) throws IOException {
        String id = itemId(relativePath);
        HttpResponse<Void> r = send(HttpRequest.newBuilder(URI.create(GRAPH + "/drives/" + driveId + "/items/" + id + "/content"))
                .header("Authorization", "Bearer " + token()).GET(), HttpResponse.BodyHandlers.discarding());
        String location = r.headers().firstValue("Location").orElse(null);
        if (r.statusCode() / 100 != 3 || location == null) {
            throw new IOException("Die Datei konnte nicht geladen werden (HTTP " + r.statusCode() + ").");
        }
        URI dl = URI.create(location);
        String h = dl.getHost() == null ? "" : dl.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(dl.getScheme()) || !(h.endsWith(".sharepoint.com") || h.endsWith(".svc.ms"))) {
            throw new IOException("Unerwartete Download-Adresse von Microsoft Graph.");
        }
        // pre-authenticated download URL: NO bearer token is sent to it
        HttpResponse<InputStream> body = send(HttpRequest.newBuilder(dl).timeout(Duration.ofMinutes(5)).GET(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (body.statusCode() != 200) {
            body.body().close();
            throw new IOException("Die Datei konnte nicht geladen werden (HTTP " + body.statusCode() + ").");
        }
        return body.body();
    }

    @Override
    public FileAcl acl(String relativePath) throws IOException {
        String id = itemId(relativePath);
        Set<String> allow = new LinkedHashSet<>();
        String url = GRAPH + "/drives/" + driveId + "/items/" + id + "/permissions";
        while (url != null) {
            JsonNode page = get(url);
            for (JsonNode p : page.path("value")) {
                if (!grantsRead(p.path("roles"))) continue;
                JsonNode link = p.path("link");
                if (!link.isMissingNode()) {
                    String scope = link.path("scope").asString("");
                    if (scope.equals("organization") || scope.equals("anonymous")) {
                        allow.add("entra-tenant:" + tenantGuid());
                    }
                }
                addIdentity(allow, p.path("grantedToV2"));
                for (JsonNode ident : p.path("grantedToIdentitiesV2")) addIdentity(allow, ident);
            }
            String next = page.path("@odata.nextLink").asString(null);
            url = next != null && next.startsWith(GRAPH + "/") ? next : null;
        }
        return new FileAcl(allow, Set.of());
    }

    private void addIdentity(Set<String> allow, JsonNode ident) {
        if (ident == null || ident.isMissingNode()) return;
        String user = ident.path("user").path("id").asString(null);
        String group = ident.path("group").path("id").asString(null);
        if (user != null && !user.isBlank()) allow.add("entra:" + user.toLowerCase(Locale.ROOT));
        if (group != null && !group.isBlank()) allow.add("entra:" + group.toLowerCase(Locale.ROOT));
        JsonNode siteGroup = ident.path("siteGroup");
        if (!siteGroup.isMissingNode() && ownerGroupId != null
                && OWNER_GROUP_NAME.matcher(siteGroup.path("displayName").asString("")).matches()) {
            allow.add("entra:" + ownerGroupId); // Mitglieder/Besitzer = verbundene M365-Gruppe
        }
        // andere SharePoint-Gruppen (z. B. Besucher) und reine SharePoint-Benutzer: nicht übernommen (fail-closed)
    }

    private static boolean grantsRead(JsonNode roles) {
        for (JsonNode r : roles) {
            String v = r.asString("").toLowerCase(Locale.ROOT);
            if (v.equals("read") || v.equals("write") || v.equals("owner") || v.equals("sp.full control")) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ http

    private String itemId(String relativePath) throws IOException {
        String id = itemIds.get(relativePath);
        if (id != null) return id;
        JsonNode item = get(GRAPH + "/drives/" + driveId + "/items/" + rootItemId + ":/" + encPath(relativePath) + "?$select=id");
        id = item.path("id").asString(null);
        if (id == null) throw new IOException("Datei nicht gefunden.");
        itemIds.put(relativePath, id);
        return id;
    }

    private JsonNode get(String url) throws IOException {
        HttpResponse<byte[]> r = send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token()).header("Accept", "application/json").GET(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() == 403 || r.statusCode() == 401) {
            throw new IOException("Microsoft Graph verweigert den Zugriff. Ist die Site für die App freigegeben (Sites.Selected)?");
        }
        if (r.statusCode() == 404) throw new IOException("Site, Bibliothek oder Ordner wurde nicht gefunden.");
        if (r.statusCode() / 100 != 2) throw new IOException("Microsoft Graph antwortet mit HTTP " + r.statusCode() + ".");
        return json.readTree(r.body());
    }

    /** Sends with up to 3 retries on throttling (429/503, Retry-After ≤ 30 s). */
    private <T> HttpResponse<T> send(HttpRequest.Builder b, HttpResponse.BodyHandler<T> handler) throws IOException {
        HttpRequest req = b.build();
        for (int attempt = 0; ; attempt++) {
            HttpResponse<T> r;
            try {
                r = http.send(req, handler);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("abgebrochen");
            }
            if ((r.statusCode() == 429 || r.statusCode() == 503) && attempt < 3) {
                long wait = r.headers().firstValue("Retry-After").map(v -> {
                    try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return 5L; }
                }).orElse(5L);
                try {
                    Thread.sleep(Math.min(30, Math.max(1, wait)) * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("abgebrochen");
                }
                continue;
            }
            return r;
        }
    }

    private synchronized String token() throws IOException {
        if (token != null && Instant.now().isBefore(tokenExpires.minusSeconds(120))) return token;
        String form = "client_id=" + enc(clientId) + "&client_secret=" + enc(secret)
                + "&scope=" + enc("https://graph.microsoft.com/.default") + "&grant_type=client_credentials";
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://login.microsoftonline.com/" + enc(tenant)
                        + "/oauth2/v2.0/token")).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<byte[]> r;
        try {
            r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("abgebrochen");
        }
        if (r.statusCode() != 200) {
            throw new IOException("Anmeldung bei Microsoft fehlgeschlagen: Tenant, Client-ID oder geheimen Schlüssel prüfen.");
        }
        JsonNode t = json.readTree(r.body());
        token = t.path("access_token").asString(null);
        tokenExpires = Instant.now().plusSeconds(t.path("expires_in").asLong(3600));
        if (token == null) throw new IOException("Microsoft hat kein Zugriffstoken geliefert.");
        tenantGuid = tidFromToken(token);
        return token;
    }

    private String tenantGuid() throws IOException {
        if (tenantGuid == null) token();
        if (tenantGuid == null) throw new IOException("Tenant-ID konnte nicht ermittelt werden.");
        return tenantGuid;
    }

    /** Reads the tid claim of OUR OWN access token (received over TLS from Microsoft; not a trust decision). */
    private String tidFromToken(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            JsonNode payload = json.readTree(java.util.Base64.getUrlDecoder().decode(parts[1]));
            String tid = payload.path("tid").asString(null);
            return tid == null ? null : tid.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encPath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) continue;
            if (!sb.isEmpty()) sb.append('/');
            sb.append(enc(part));
        }
        return sb.toString();
    }

    private static String decode(String v) {
        return URLDecoder.decode(v.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        http.close();
    }
}
