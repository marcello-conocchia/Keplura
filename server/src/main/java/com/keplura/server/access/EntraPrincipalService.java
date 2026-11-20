package com.keplura.server.access;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ADR-0012 §2 (DS-4): Entra-ID-Prinzipale aus dem verifizierten ID-Token der
 * OIDC-Anmeldung — {@code entra:<oid>} plus {@code entra:<gruppen-id>} aus dem
 * {@code groups}-Claim (App-Registrierung: „groupMembershipClaims“ = SecurityGroup
 * bzw. All). Keine zusätzliche Graph-Berechtigung nötig.
 *
 * <p>Gruppen-Überlauf (> 200 Gruppen, Claim fehlt, {@code _claim_names.groups}
 * gesetzt): nur die Benutzer-ID wird übernommen (fail-closed für Gruppenrechte),
 * Warnung im Log. Aufgefrischt wird bei jeder Anmeldung.
 */
@Service
public class EntraPrincipalService {

    private static final Logger log = LoggerFactory.getLogger(EntraPrincipalService.class);
    private static final Pattern GUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final int MAX_GROUPS = 500;

    private final UserPrincipalRepository repository;

    public EntraPrincipalService(UserPrincipalRepository repository) {
        this.repository = repository;
    }

    /** Ignores tokens without a GUID {@code oid} claim (non-Entra identity providers). */
    @Transactional
    public void applyFromIdToken(Long userId, JsonNode payload) {
        String oid = payload.path("oid").asString(null);
        if (oid == null || !GUID.matcher(oid).matches()) {
            return; // not an Entra token: leave everything untouched
        }
        // Only Microsoft-issued tokens may carry Entra principals: any other IdP could put an
        // arbitrary "oid" into its token. Issuer must be the Entra endpoint of the token's own tenant.
        String iss = payload.path("iss").asString("");
        String tid = payload.path("tid").asString("");
        if (!GUID.matcher(tid).matches()
                || !(iss.equalsIgnoreCase("https://login.microsoftonline.com/" + tid + "/v2.0")
                     || iss.equalsIgnoreCase("https://sts.windows.net/" + tid + "/"))) {
            log.warn("entra principals ignored: token issuer is not Microsoft Entra (user {})", userId);
            return;
        }
        Set<String> principals = new LinkedHashSet<>();
        principals.add("entra:" + oid.toLowerCase(java.util.Locale.ROOT));
        principals.add("entra-tenant:" + tid.toLowerCase(java.util.Locale.ROOT)); // für „ganze Organisation“-Links
        JsonNode groups = payload.path("groups");
        if (groups.isArray()) {
            for (JsonNode g : groups) {
                String id = g.asString(null);
                if (id != null && GUID.matcher(id).matches() && principals.size() <= MAX_GROUPS) {
                    principals.add("entra:" + id.toLowerCase(java.util.Locale.ROOT));
                }
            }
        } else if (payload.path("_claim_names").has("groups")) {
            log.warn("entra login: group overage for user {} — only the user id is applied", userId);
        }
        repository.deleteEntraPrincipals(userId);
        repository.flush();
        for (String p : principals) {
            repository.save(new UserPrincipal(userId, p));
        }
        repository.flush();
    }
}
