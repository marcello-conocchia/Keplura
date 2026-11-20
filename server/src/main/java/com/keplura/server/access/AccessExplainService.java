package com.keplura.server.access;

import com.keplura.server.datasource.DataSourceFileRepository;
import com.keplura.server.datasource.DataSourceRepository;
import com.keplura.server.datasource.ExposureRules;
import com.keplura.server.directory.DirectoryService;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import com.keplura.server.knowledge.Document;
import com.keplura.server.knowledge.DocumentRepository;
import com.keplura.server.knowledge.KnowledgeBase;
import com.keplura.server.knowledge.KnowledgeBaseRepository;
import com.keplura.server.org.MembershipRepository;
import com.keplura.server.org.OrganizationRepository;
import com.keplura.server.space.Space;
import com.keplura.server.space.SpaceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rechte-Nachweis für Admins: „Darf diese Person dieses Dokument sehen, und warum?“
 *
 * <p>Das <b>Urteil</b> kommt ausschließlich aus derselben Abfrage, die Suche,
 * Liste und Vorschau verwenden ({@link DocumentRepository#findByIdInKbForMember},
 * also Mandanten-Pins + {@link AccessFilter}). Die Begründung wird daneben aus den
 * gespeicherten Rechten und Gruppen abgeleitet; weicht sie vom Urteil ab, wird das
 * offen gemeldet statt still überdeckt. Liest nur, schreibt nichts.
 */
@Service
public class AccessExplainService {

    public record UserHit(Long id, String email, String displayName, int groups, Instant groupsRefreshedAt) {
    }

    public record DocumentHit(Long id, String filename, String location, String origin) {
    }

    /** ok: true = spricht für Zugriff, false = verhindert ihn, null = nur Information. */
    public record Step(Boolean ok, String title, String detail) {
    }

    public record AclRow(String principal, String label, boolean allow, boolean matchesUser) {
    }

    public record Explanation(Long userId, String userName, Long documentId, String filename, String location,
                              boolean visible, boolean consistent, List<Step> steps, List<AclRow> acl,
                              List<AclRow> userGroups, Instant groupsRefreshedAt) {
    }

    public record KbRow(Long kbId, String location, long total, long visible) {
    }

    public record Overview(Long userId, String userName, List<KbRow> knowledgeBases, Instant groupsRefreshedAt) {
    }

    static final int MAX_LISTED_GROUPS = 50;

    private final UserRepository users;
    private final UserPrincipalRepository principals;
    private final DocumentRepository documents;
    private final DocumentAclRepository acls;
    private final KnowledgeBaseRepository kbs;
    private final SpaceRepository spaces;
    private final OrganizationRepository orgs;
    private final MembershipRepository memberships;
    private final DataSourceFileRepository sourceFiles;
    private final DataSourceRepository sources;
    private DirectoryService directory;

    public AccessExplainService(UserRepository users, UserPrincipalRepository principals, DocumentRepository documents,
                                DocumentAclRepository acls, KnowledgeBaseRepository kbs, SpaceRepository spaces,
                                OrganizationRepository orgs, MembershipRepository memberships,
                                DataSourceFileRepository sourceFiles, DataSourceRepository sources) {
        this.users = users;
        this.principals = principals;
        this.documents = documents;
        this.acls = acls;
        this.kbs = kbs;
        this.spaces = spaces;
        this.orgs = orgs;
        this.memberships = memberships;
        this.sourceFiles = sourceFiles;
        this.sources = sources;
    }

    @Autowired(required = false)
    void setDirectory(DirectoryService directory) {
        this.directory = directory;
    }

    // ------------------------------------------------------------------ Suche

    @Transactional(readOnly = true)
    public List<UserHit> findUsers(String q) {
        String term = q == null ? "" : q.trim();
        return users.findTop20ByEmailContainingIgnoreCaseOrDisplayNameContainingIgnoreCaseOrderByDisplayNameAsc(term, term)
                .stream().map(u -> {
                    List<UserPrincipal> p = principals.findByUserId(u.getId());
                    return new UserHit(u.getId(), u.getEmail(), u.getDisplayName(), p.size(), refreshed(p));
                }).toList();
    }

    @Transactional(readOnly = true)
    public List<DocumentHit> findDocuments(String q) {
        String term = q == null ? "" : q.trim();
        if (term.isEmpty()) return List.of();
        return documents.findTop20ByFilenameContainingIgnoreCaseOrderByIdDesc(term).stream()
                .map(d -> new DocumentHit(d.getId(), d.getFilename(), location(d.getKbId()), origin(d.getId())))
                .toList();
    }

    // ------------------------------------------------------------------ Erklärung

    @Transactional(readOnly = true)
    public Explanation explain(Long userId, Long documentId) {
        User user = users.findById(userId).orElseThrow(() -> new NotFound("Person nicht gefunden."));
        Document doc = documents.findById(documentId).orElseThrow(() -> new NotFound("Dokument nicht gefunden."));
        KnowledgeBase kb = kbs.findById(doc.getKbId()).orElseThrow(() -> new NotFound("Wissensbasis nicht gefunden."));
        Space space = spaces.findById(kb.getSpaceId()).orElseThrow(() -> new NotFound("Arbeitsbereich nicht gefunden."));
        Long orgId = space.getOrgId();
        String name = user.getDisplayName();

        // Maßgeblich: genau die Abfrage, die auch Suche/Liste/Vorschau verwenden.
        boolean visible = documents.findByIdInKbForMember(documentId, kb.getId(), space.getId(), orgId, userId).isPresent();

        List<UserPrincipal> userPrincipals = principals.findByUserId(userId);
        Set<String> mine = new LinkedHashSet<>();
        for (UserPrincipal p : userPrincipals) mine.add(p.getPrincipal());
        List<DocumentAcl> entries = acls.findByDocumentId(documentId);

        Set<String> toName = new LinkedHashSet<>(mine);
        for (DocumentAcl a : entries) toName.add(a.getPrincipal());
        Map<String, String> names = names(orgId, toName);

        List<Step> steps = new ArrayList<>();
        boolean member = memberships.findByOrgIdAndUserId(orgId, userId).isPresent();
        String orgName = orgs.findById(orgId).map(o -> o.getName()).orElse("Organisation " + orgId);
        steps.add(member
                ? new Step(true, name + " ist Mitglied von „" + orgName + "“.", null)
                : new Step(false, name + " ist kein Mitglied von „" + orgName + "“.",
                "Ohne Mitgliedschaft sieht niemand Dokumente dieser Organisation, egal welche Dateirechte gelten."));

        boolean derived;
        List<AclRow> aclRows = new ArrayList<>();
        if (entries.isEmpty()) {
            steps.add(new Step(null, "Hand-Upload ohne eigene Dateirechte.",
                    "Solche Dokumente sehen alle Mitglieder der Organisation."));
            derived = member;
        } else {
            steps.add(new Step(null, "Aus " + origin(documentId) + " mit übernommenen Dateirechten.", null));
            boolean failClosed = entries.stream().noneMatch(DocumentAcl::isAllowRead)
                    && entries.stream().anyMatch(a -> !a.isAllowRead() && AccessFilter.EVERYONE.equals(a.getPrincipal()));
            List<String> allowHits = new ArrayList<>(), denyHits = new ArrayList<>();
            for (DocumentAcl a : entries) {
                boolean applies = AccessFilter.EVERYONE.equals(a.getPrincipal()) || mine.contains(a.getPrincipal());
                aclRows.add(new AclRow(a.getPrincipal(), label(a.getPrincipal(), names), a.isAllowRead(), applies));
                if (applies) (a.isAllowRead() ? allowHits : denyHits).add(label(a.getPrincipal(), names));
            }
            aclRows.sort(Comparator.comparing((AclRow r) -> !r.matchesUser()).thenComparing(r -> !r.allow())
                    .thenComparing(AclRow::label));
            if (failClosed) {
                steps.add(new Step(false, "Die Rechte dieser Datei konnten nicht gelesen werden.",
                        "Solche Dateien sind für niemanden sichtbar, bis der nächste Abgleich die Rechte lesen kann "
                                + "(siehe Datenquellen → Probleme ansehen)."));
            } else if (allowHits.isEmpty()) {
                steps.add(new Step(false, "Keine Leseberechtigung passt zu " + name + ".",
                        userPrincipals.isEmpty()
                                ? name + " hat noch keine Verzeichnisgruppen. Diese entstehen bei der Anmeldung über "
                                + "das Verzeichnis (AD/LDAP) bzw. mit Microsoft 365."
                                : "Die Datei ist nur für andere Personen oder Gruppen freigegeben."));
            } else {
                steps.add(new Step(true, "Lesen erlaubt über: " + String.join(", ", allowHits) + ".", null));
            }
            if (!denyHits.isEmpty()) {
                steps.add(new Step(false, "Ausdrücklich verweigert über: " + String.join(", ", denyHits) + ".",
                        "Ein Verbot hat immer Vorrang vor einer Erlaubnis, wie im Explorer."));
            }
            derived = member && !failClosed && !allowHits.isEmpty() && denyHits.isEmpty();
        }

        steps.add(visible
                ? new Step(true, "Ergebnis: " + name + " findet dieses Dokument in Suche und Chat.", null)
                : new Step(false, "Ergebnis: " + name + " findet dieses Dokument nicht.", null));
        boolean consistent = derived == visible;
        if (!consistent) {
            steps.add(new Step(null, "Hinweis: Die Begründung weicht vom tatsächlichen Filter ab.",
                    "Maßgeblich ist das Ergebnis oben (dieselbe Abfrage wie die Suche). Bitte als Fehler melden."));
        }

        List<AclRow> groups = new ArrayList<>();
        for (String p : mine) {
            if (groups.size() >= MAX_LISTED_GROUPS) break;
            boolean allowed = entries.stream().anyMatch(a -> a.isAllowRead() && a.getPrincipal().equals(p));
            boolean denied = entries.stream().anyMatch(a -> !a.isAllowRead() && a.getPrincipal().equals(p));
            groups.add(new AclRow(p, label(p, names), !denied, allowed || denied));
        }
        groups.sort(Comparator.comparing((AclRow r) -> !r.matchesUser()).thenComparing(AclRow::label));

        return new Explanation(userId, name, documentId, doc.getFilename(), location(kb.getId()), visible, consistent,
                steps, aclRows, groups, refreshed(userPrincipals));
    }

    // ------------------------------------------------------------------ Übersicht

    @Transactional(readOnly = true)
    public Overview overview(Long userId) {
        User user = users.findById(userId).orElseThrow(() -> new NotFound("Person nicht gefunden."));
        List<KbRow> rows = new ArrayList<>();
        for (var m : memberships.findByUserId(userId)) {
            for (Space s : spaces.findByOrgId(m.getOrgId())) {
                for (KnowledgeBase kb : kbs.findBySpaceId(s.getId())) {
                    long total = documents.countByKbId(kb.getId());
                    long seen = documents.countAllInKbForMember(kb.getId(), s.getId(), m.getOrgId(), userId);
                    rows.add(new KbRow(kb.getId(), location(kb.getId()), total, seen));
                }
            }
        }
        rows.sort(Comparator.comparing(KbRow::location));
        return new Overview(userId, user.getDisplayName(), rows, refreshed(principals.findByUserId(userId)));
    }

    // ------------------------------------------------------------------ Hilfen

    public static class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }

    private Map<String, String> names(Long orgId, Set<String> keys) {
        if (directory == null || keys.isEmpty()) return Map.of();
        try {
            return directory.resolveNames(orgId, List.copyOf(keys));
        } catch (RuntimeException e) {
            return Map.of(); // Verzeichnis nicht erreichbar: technische Namen bleiben sichtbar
        }
    }

    static String label(String principal, Map<String, String> names) {
        String resolved = names.get(principal);
        if (resolved != null) return resolved;
        String broad = ExposureRules.broadLabel(principal);
        if (broad != null) return broad;
        if (principal.startsWith("posix-uid:")) return "Linux-Benutzer (UID " + principal.substring(10) + ")";
        if (principal.startsWith("posix-gid:")) return "Linux-Gruppe (GID " + principal.substring(10) + ")";
        if (principal.startsWith("ldap-dn:")) return principal.substring(8);
        if (principal.startsWith("sid:")) return "Windows-Konto/-Gruppe " + principal.substring(4);
        if (principal.startsWith("entra:")) return "Microsoft-365-Benutzer/-Gruppe";
        return principal;
    }

    private String location(Long kbId) {
        return kbs.findById(kbId).map(kb -> {
            String space = spaces.findById(kb.getSpaceId()).map(Space::getName).orElse("?");
            return space + " › " + kb.getName();
        }).orElse("Wissensbasis " + kbId);
    }

    private String origin(Long documentId) {
        return sourceFiles.findFirstByDocumentId(documentId)
                .flatMap(f -> sources.findById(f.getDataSourceId())
                        .map(ds -> "Datenquelle „" + ds.getDisplayName() + "“ (" + f.getRelativePath() + ")"))
                .orElse("Hand-Upload");
    }

    private static Instant refreshed(List<UserPrincipal> p) {
        return p.stream().map(UserPrincipal::getRefreshedAt).filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
    }
}
