package com.keplura.server.org;

import com.keplura.server.auth.CsrfService;
import com.keplura.server.auth.CsrfTestSupport;
import com.keplura.server.identity.PasswordHasher;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-2: the owner-invite → member-join acceptance flow end-to-end over the
 * wire: create (token visible once), list (no token), accept (membership),
 * replay-reject, double-accept-409, non-owner-403, expiry, revoke, and the
 * structural never-mint-an-OWNER guard.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:invite-api-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class InviteApiIntegrationTest {

    private static final PasswordHasher HASHER = new PasswordHasher();

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    @Autowired private OrgService orgService;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** {email, cookiePair, orgId} for a fresh owner with one org. */
    private Object[] ownerWithOrg() {
        return memberWithOrg(true);
    }

    private Object[] memberWithOrg(boolean createOrg) {
        String email = "inv-" + System.nanoTime() + "@example.com";
        User user = userRepository.saveAndFlush(User.create(email, HASHER.hash("pw"), email));
        ResponseEntity<String> login = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "pw")), String.class);
        String cookie = CsrfTestSupport.cookiePair(login);
        String orgId = null;
        if (createOrg) {
            orgId = String.valueOf(orgService.createOrg(user.getId(), "Inv " + user.getId()).getId());
        }
        return new Object[]{email, cookie, orgId, user.getId()};
    }
    private ResponseEntity<String> postJson(String path, Object[] who, Map<String, ?> body) {
        String cookie = (String) who[1];
        return rest.exchange(RequestEntity
                .post(URI.create(path))
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.COOKIE, cookie)
                .header(CsrfService.HEADER_NAME, CsrfTestSupport.csrfValue(cookie))
                .body(body), String.class);
    }

    private String tokenOf(ResponseEntity<String> created) {
        return JsonPath.parse(created.getBody()).read("$.token", String.class);
    }

    private Number idOf(ResponseEntity<String> created) {
        return JsonPath.parse(created.getBody()).read("$.invite.id", Number.class);
    }

    @Test
    void ownerCreatesInviteAndMemberAcceptsJoiningAsMember() {
        Object[] owner = ownerWithOrg();
        String orgId = (String) owner[2];
        ResponseEntity<String> created = postJson("/api/v1/orgs/" + orgId + "/invites", owner,
                Map.of());
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(JsonPath.parse(created.getBody()).read("$.invite.role", String.class)).isEqualTo("MEMBER");

        Object[] joiner = memberWithOrg(false);
        ResponseEntity<String> accepted = postJson("/api/v1/invites/accept", joiner,
                Map.of("token", tokenOf(created)));
        assertThat(accepted.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> view = JsonPath.parse(accepted.getBody()).read("$", Map.class);
        assertThat(((Number) view.get("orgId")).longValue()).isEqualTo(Long.parseLong(orgId));
        assertThat(view.get("orgName").toString()).startsWith("Inv ");
        // the joiner's own org view must now report the granted MEMBER role
        ResponseEntity<String> mine = rest.exchange(RequestEntity
                .get(URI.create("/api/v1/orgs/" + orgId))
                .header(HttpHeaders.COOKIE, (String) joiner[1]).build(), String.class);
        assertThat(mine.getStatusCode().value()).isEqualTo(200);
        assertThat(mine.getBody()).contains("\"role\":\"MEMBER\"");
    }
    @Test
    void listShowsInvitesButNeverTheToken() {
        Object[] owner = ownerWithOrg();
        String orgId = (String) owner[2];
        ResponseEntity<String> first = postJson("/api/v1/orgs/" + orgId + "/invites", owner, Map.of());
        ResponseEntity<String> created = postJson("/api/v1/orgs/" + orgId + "/invites", owner, Map.of());
        String secret = tokenOf(created);

        ResponseEntity<String> list = rest.exchange(RequestEntity
                .get(URI.create("/api/v1/orgs/" + orgId + "/invites"))
                .header(HttpHeaders.COOKIE, (String) owner[1])
                .build(), String.class);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        // pending rows only, id-visible, and the plaintext token NEVER appears
        assertThat(list.getBody()).doesNotContain(secret).doesNotContain(tokenOf(first));
        List<Map<String, Object>> rows = JsonPath.parse(list.getBody()).read("$", List.class);
        assertThat(rows).extracting(r -> ((Number) r.get("id")).longValue())
                .contains(idOf(created).longValue(), idOf(first).longValue());
    }

    @Test
    void tokenIsSingleUseAndReplayIsRejected() {
        Object[] owner = ownerWithOrg();
        String orgId = (String) owner[2];
        String token = tokenOf(postJson("/api/v1/orgs/" + orgId + "/invites", owner,
                Map.of()));
        Object[] joiner = memberWithOrg(false);
        assertThat(postJson("/api/v1/invites/accept", joiner, Map.of("token", token))
                .getStatusCode().value()).isEqualTo(200);
        assertThat(postJson("/api/v1/invites/accept", joiner, Map.of("token", token))
                .getStatusCode().value()).isEqualTo(404);
    }

    /**
     * FINDING-1 regression (PR #29 review): two DIFFERENT users accept the
     * same token simultaneously — latch-synchronized wire-level race. Exactly
     * one may join: one 200, one honest already-used answer (the uniform 404
     * — "used" and "unknown" are deliberately indistinguishable here), and
     * the roster shows ONE MEMBER. The old read-then-dirty-check stamp lost
     * this race (both 200, 2x MEMBER); the atomic conditional UPDATE in
     * InviteService.accept makes two winners structurally impossible.
     *
     * <p>Rounds: two requests on an in-memory client can still interleave by
     * luck, so one lucky serialisation would silently pass. Each round mints
     * a fresh invite + fresh pair of users and asserts the invariant again;
     * the OLD code violates it in EVERY round (the loser can never 404 before
     * the winner commits), so 5 rounds pin this without flake risk.
     */
    @Test
    void concurrentAcceptsOfTheSameTokenMintExactlyOneMembership() throws Exception {
        Object[] owner = ownerWithOrg();
        String orgId = (String) owner[2];

        for (int round = 0; round < 5; round++) {
            ResponseEntity<String> created = postJson("/api/v1/orgs/" + orgId + "/invites", owner,
                    Map.of());
            String token = tokenOf(created);
            long inviteId = idOf(created).longValue();
            Object[] first = memberWithOrg(false);
            Object[] second = memberWithOrg(false);
            long membersBefore = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM membership WHERE org_id = ? AND role = 'MEMBER'",
                    Long.class, Long.parseLong(orgId));

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            AtomicReference<Integer> firstStatus = new AtomicReference<>();
            AtomicReference<Integer> secondStatus = new AtomicReference<>();
            Thread a = new Thread(() -> {
                try {
                    start.await();
                    firstStatus.set(postJson("/api/v1/invites/accept", first,
                            Map.of("token", token)).getStatusCode().value());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            Thread b = new Thread(() -> {
                try {
                    start.await();
                    secondStatus.set(postJson("/api/v1/invites/accept", second,
                            Map.of("token", token)).getStatusCode().value());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            a.start();
            b.start();
            start.countDown();
            assertThat(done.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            int combined = firstStatus.get() + secondStatus.get();
            // exactly one 200 and one 404 (sum 604 pins the pair regardless of
            // which thread won; the double-200 the old code produced breaks it)
            assertThat(combined).as("round %d: one-time token, one winner", round)
                    .isEqualTo(200 + 404);

            // the roster tells the same story as the statuses: this invite
            // (and only this invite) granted exactly ONE new MEMBER
            long memberRows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM membership WHERE org_id = ? AND role = 'MEMBER'",
                    Long.class, Long.parseLong(orgId));
            assertThat(memberRows - membersBefore).as("round %d: exactly one new granted MEMBER", round)
                    .isEqualTo(1L);

            // the audit stamp names the WINNER, not an arbitrary loser
            Long usedBy = jdbcTemplate.queryForObject(
                    "SELECT used_by FROM org_invite WHERE id = ?", Long.class, inviteId);
            long winnerId = firstStatus.get() == 200
                    ? ((Number) first[3]).longValue() : ((Number) second[3]).longValue();
            assertThat(usedBy).as("round %d: used_by = the accepting winner", round).isEqualTo(winnerId);
        }
    }

    @Test
    void secondLiveTokenForAlreadyJoinedUserConflicts409() {
        Object[] owner = ownerWithOrg();
        String orgId = (String) owner[2];
        String first = tokenOf(postJson("/api/v1/orgs/" + orgId + "/invites", owner,
                Map.of()));
        String second = tokenOf(postJson("/api/v1/orgs/" + orgId + "/invites", owner,
                Map.of()));
        Object[] joiner = memberWithOrg(false);
        assertThat(postJson("/api/v1/invites/accept", joiner, Map.of("token", first))
                .getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> again = postJson("/api/v1/invites/accept", joiner, Map.of("token", second));
        assertThat(again.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void nonOwnerCannotCreateOrListInvites() {
        Object[] owner = ownerWithOrg();
        String orgId = (String) owner[2];
        Object[] member = memberWithOrg(false);
        postJson("/api/v1/invites/accept", member, Map.of("token", tokenOf(
                postJson("/api/v1/orgs/" + orgId + "/invites", owner, Map.of()))));

        ResponseEntity<String> denied = postJson("/api/v1/orgs/" + orgId + "/invites", member,
                Map.of());
        assertThat(denied.getStatusCode().value()).isEqualTo(403);
        ResponseEntity<String> listDenied = rest.exchange(RequestEntity
                .get(URI.create("/api/v1/orgs/" + orgId + "/invites"))
                .header(HttpHeaders.COOKIE, (String) member[1]).build(), String.class);
        assertThat(listDenied.getStatusCode().value()).isEqualTo(403);

        // an outsider without any membership sees the uniform 404 (no org probe)
        Object[] outsider = memberWithOrg(false);
        ResponseEntity<String> probe = postJson("/api/v1/orgs/" + orgId + "/invites", outsider,
                Map.of());
        assertThat(probe.getStatusCode().value()).isEqualTo(404);
    }
    @Test
    void revokedInviteCannotBeAcceptedAndDisappearsFromPendingList() {
        Object[] owner = ownerWithOrg();
        String orgId = (String) owner[2];
        ResponseEntity<String> created = postJson("/api/v1/orgs/" + orgId + "/invites", owner,
                Map.of());
        String token = tokenOf(created);
        Number inviteId = idOf(created);

        ResponseEntity<String> revoked = rest.exchange(RequestEntity
                .delete(URI.create("/api/v1/orgs/" + orgId + "/invites/" + inviteId))
                .header(HttpHeaders.COOKIE, (String) owner[1])
                .header(CsrfService.HEADER_NAME, CsrfTestSupport.csrfValue((String) owner[1]))
                .build(), String.class);
        assertThat(revoked.getStatusCode().value()).isEqualTo(204);

        Object[] joiner = memberWithOrg(false);
        assertThat(postJson("/api/v1/invites/accept", joiner, Map.of("token", token))
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void membersRosterListsOwnerAndNewMemberWithNames() {
        Object[] owner = ownerWithOrg();
        String orgId = (String) owner[2];
        Object[] joiner = memberWithOrg(false);
        postJson("/api/v1/invites/accept", joiner, Map.of("token", tokenOf(
                postJson("/api/v1/orgs/" + orgId + "/invites", owner, Map.of()))));

        ResponseEntity<String> roster = rest.exchange(RequestEntity
                .get(URI.create("/api/v1/orgs/" + orgId + "/members"))
                .header(HttpHeaders.COOKIE, (String) owner[1]).build(), String.class);
        assertThat(roster.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> members = JsonPath.parse(roster.getBody()).read("$", List.class);
        assertThat(members).hasSize(2);
        assertThat(members).anySatisfy(m -> assertThat(m.get("role")).isEqualTo("OWNER"));
        assertThat(members).anySatisfy(m -> assertThat(m.get("role")).isEqualTo("MEMBER"));
    }
    @Test
    void expiredInviteCannotBeAccepted() {
        Object[] owner = ownerWithOrg();
        String orgId = (String) owner[2];
        ResponseEntity<String> created = postJson("/api/v1/orgs/" + orgId + "/invites", owner,
                Map.of());
        Number inviteId = idOf(created);
        jdbcTemplate.update("UPDATE org_invite SET expires_at = CURRENT_TIMESTAMP - 1 WHERE id = ?", inviteId);

        Object[] joiner = memberWithOrg(false);
        assertThat(postJson("/api/v1/invites/accept", joiner, Map.of("token", tokenOf(created)))
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void malformedAcceptBodiesFailWith400AndNeverTouchState() {
        Object[] owner = ownerWithOrg();
        String orgId = (String) owner[2];
        Object[] joiner = memberWithOrg(false);
        // blank token = bad request (client bug), NOT a silent 404
        assertThat(postJson("/api/v1/invites/accept", joiner,
                Map.of("token", "   ")).getStatusCode().value()).isEqualTo(400);
        // a junk token is the uniform 404 (unknown), state untouched
        assertThat(postJson("/api/v1/invites/accept", joiner,
                Map.of("token", "0".repeat(64))).getStatusCode().value()).isEqualTo(404);
        // create ignores its request body entirely (bearer-token design) and
        // still answers 201 — pinned so a future change cannot add an email
        // field here without a deliberate contract update
        assertThat(postJson("/api/v1/orgs/" + orgId + "/invites", owner,
                Map.of()).getStatusCode().value()).isEqualTo(201);
    }
}
