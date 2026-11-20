package com.keplura.server.access;

import com.keplura.server.auth.CsrfTestSupport;
import com.keplura.server.identity.PasswordHasher;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import com.keplura.server.knowledge.Document;
import com.keplura.server.knowledge.DocumentRepository;
import com.keplura.server.org.Membership;
import com.keplura.server.org.MembershipRepository;
import com.keplura.server.org.MembershipRole;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rechte-Nachweis: Das Urteil muss in jedem Fall mit dem echten Such-Filter übereinstimmen,
 * die Begründung muss die richtige Ursache nennen, und nur Admins dürfen fragen.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:access-explain-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "keplura.secrets.master-key=access-explain-test-key"
})
class AccessExplainIntegrationTest {

    private static final PasswordHasher HASHER = new PasswordHasher();
    private static final String SALES = "sid:S-1-5-21-1-2-3-1105";
    private static final String HR = "sid:S-1-5-21-1-2-3-1106";

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository users;
    @Autowired private MembershipRepository memberships;
    @Autowired private DocumentRepository documents;
    @Autowired private DocumentAclRepository acls;
    @Autowired private UserPrincipalRepository principals;

    private String adminCookies, userCookies;
    private Long anna, outsider, kbId;
    private Long handUpload, salesDoc, hrDoc, deniedDoc, unreadableDoc;

    @BeforeEach
    void setUp() {
        String s = Long.toString(System.nanoTime());
        User admin = User.create("acc-admin-" + s + "@example.test", HASHER.hash("admin-pw"), "Admin");
        admin.promoteToAdmin();
        users.saveAndFlush(admin);
        anna = users.saveAndFlush(User.create("anna-" + s + "@example.test", HASHER.hash("anna-pw"), "Anna " + s)).getId();
        outsider = users.saveAndFlush(User.create("out-" + s + "@example.test", HASHER.hash("out-pw"), "Olaf " + s)).getId();
        adminCookies = CsrfTestSupport.cookiePair(login(admin.getEmail(), "admin-pw"));
        userCookies = CsrfTestSupport.cookiePair(login("anna-" + s + "@example.test", "anna-pw"));
        String csrf = CsrfTestSupport.csrfValue(adminCookies);

        Long org = id(post("/api/v1/orgs", "{\"name\":\"Firma " + s + "\"}", csrf));
        Long space = id(post("/api/v1/orgs/" + org + "/spaces", "{\"name\":\"Ablage\"}", csrf));
        kbId = id(post("/api/v1/orgs/" + org + "/spaces/" + space + "/knowledge-bases", "{\"name\":\"Fileserver\"}", csrf));
        memberships.saveAndFlush(Membership.grant(org, anna, MembershipRole.MEMBER));
        principals.saveAndFlush(new UserPrincipal(anna, SALES));

        handUpload = doc("Handbuch.pdf");
        salesDoc = doc("Angebot.pdf");
        acls.saveAndFlush(new DocumentAcl(salesDoc, SALES, true));
        hrDoc = doc("Gehaltsliste.xlsx");
        acls.saveAndFlush(new DocumentAcl(hrDoc, HR, true));
        deniedDoc = doc("Projekt.docx");
        acls.saveAndFlush(new DocumentAcl(deniedDoc, AccessFilter.EVERYONE, true));
        acls.saveAndFlush(new DocumentAcl(deniedDoc, SALES, false));
        unreadableDoc = doc("Unklar.pdf");
        acls.saveAndFlush(new DocumentAcl(unreadableDoc, AccessFilter.EVERYONE, false));
    }

    private Long doc(String name) {
        return documents.saveAndFlush(Document.of(kbId, name, "application/pdf", 10, "0".repeat(64), "x/" + name, anna)).getId();
    }

    private ResponseEntity<String> login(String email, String password) {
        return rest.exchange(RequestEntity.post(URI.create("/api/v1/auth/login")).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password)), String.class);
    }

    private ResponseEntity<String> post(String path, String body, String csrf) {
        return rest.exchange(RequestEntity.post(URI.create(path)).header(HttpHeaders.COOKIE, adminCookies)
                .header("X-CSRF-Token", csrf).contentType(MediaType.APPLICATION_JSON).body(body), String.class);
    }

    private static Long id(ResponseEntity<String> r) {
        assertThat(r.getStatusCode().is2xxSuccessful()).as(r.getBody()).isTrue();
        return ((Number) JsonPath.read(r.getBody(), "$.id")).longValue();
    }

    private ResponseEntity<String> get(String path, String cookies) {
        return rest.exchange(RequestEntity.get(URI.create(path)).header(HttpHeaders.COOKIE, cookies).build(), String.class);
    }

    private String explain(Long user, Long doc) {
        ResponseEntity<String> r = get("/api/v1/admin/access/explain?userId=" + user + "&documentId=" + doc, adminCookies);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(r.getBody(), "$.consistent")).as("Begründung = Filter").isTrue();
        return r.getBody();
    }

    private static List<String> titles(String body) {
        return JsonPath.read(body, "$.steps[*].title");
    }

    @Test
    void verdictsMatchTheRealFilterAndNameTheCause() {
        String hand = explain(anna, handUpload);
        assertThat((Boolean) JsonPath.read(hand, "$.visible")).isTrue();
        assertThat(titles(hand)).anyMatch(t -> t.startsWith("Hand-Upload"));

        String sales = explain(anna, salesDoc);
        assertThat((Boolean) JsonPath.read(sales, "$.visible")).isTrue();
        assertThat(titles(sales)).anyMatch(t -> t.startsWith("Lesen erlaubt über"));
        assertThat((List<Boolean>) JsonPath.read(sales, "$.acl[?(@.matchesUser == true)].allow")).containsExactly(true);

        String hr = explain(anna, hrDoc);
        assertThat((Boolean) JsonPath.read(hr, "$.visible")).isFalse();
        assertThat(titles(hr)).anyMatch(t -> t.startsWith("Keine Leseberechtigung passt"));

        String denied = explain(anna, deniedDoc);
        assertThat((Boolean) JsonPath.read(denied, "$.visible")).isFalse();
        assertThat(titles(denied)).anyMatch(t -> t.startsWith("Ausdrücklich verweigert"));

        String unreadable = explain(anna, unreadableDoc);
        assertThat((Boolean) JsonPath.read(unreadable, "$.visible")).isFalse();
        assertThat(titles(unreadable)).anyMatch(t -> t.contains("konnten nicht gelesen werden"));

        String outsiderView = explain(outsider, handUpload);
        assertThat((Boolean) JsonPath.read(outsiderView, "$.visible")).isFalse();
        assertThat(titles(outsiderView).getFirst()).contains("kein Mitglied");
    }

    @Test
    void overviewCountsWhatThePersonCanSee() {
        ResponseEntity<String> r = get("/api/v1/admin/access/overview?userId=" + anna, adminCookies);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(r.getBody(), "$.knowledgeBases[0].total")).longValue()).isEqualTo(5);
        assertThat(((Number) JsonPath.read(r.getBody(), "$.knowledgeBases[0].visible")).longValue()).isEqualTo(2);
    }

    @Test
    void searchFindsPeopleAndDocuments() {
        assertThat((List<Object>) JsonPath.read(get("/api/v1/admin/access/users?q=Anna", adminCookies).getBody(), "$[*].id"))
                .contains(anna.intValue());
        String docs = get("/api/v1/admin/access/documents?q=Gehalts", adminCookies).getBody();
        assertThat((List<String>) JsonPath.read(docs, "$[*].origin")).containsOnly("Hand-Upload");
    }

    @Test
    void onlyAdminsMayAskAndUnknownIdsAre404() {
        assertThat(get("/api/v1/admin/access/explain?userId=" + anna + "&documentId=" + salesDoc, userCookies)
                .getStatusCode().value()).isEqualTo(403);
        assertThat(get("/api/v1/admin/access/users?q=a", userCookies).getStatusCode().value()).isEqualTo(403);
        assertThat(rest.getForEntity("/api/v1/admin/access/overview?userId=" + anna, String.class)
                .getStatusCode().value()).isEqualTo(401);
        assertThat(get("/api/v1/admin/access/explain?userId=999999&documentId=" + salesDoc, adminCookies)
                .getStatusCode().value()).isEqualTo(404);
        assertThat(get("/api/v1/admin/access/users?q=" + "x".repeat(201), adminCookies)
                .getStatusCode().value()).isEqualTo(400);
    }
}
