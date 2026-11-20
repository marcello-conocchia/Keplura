# ADR-0008: Global Admin-Rolle + KI-Provider/Modell-Konfiguration in der DB (ADMIN-KI Plan)

Date: 2026-09-08
Status: Proposed (Plan für ADMIN-KI t_0b34910a; nummeriert nach merge-Basis main@fefe61d —
höchste existierende ADR ist 0007, höchste Flyway-Migration ist V8. Falls ein parallel
gemergter Zweig 0008 oder V9 bereits belegt, beim Implementierungs-Merge auf die
naechstfreie Nummer heben — der Inhalt ist nummernunabhaengig.)

Kontext: PO-Entscheid 08.09. (ADMIN-KI, t_0b34910a). Provider/Modelle sollen nicht
mehr ueber Code/.env gepflegt werden, sondern ueber einen serverseitig geschuetzten
Admin-Bereich. Dieses ADR liefert die PO-Schritte 1–3: Auth-Analyse, Provider-Inventar,
Architekturplan. KEIN Code in dieser Karte.

---

## 1. Analyse: bestehende Auth-/Rollenarchitektur (Stand main)

Fakten aus dem Code (nichts erfunden — Pfade referenzieren die Implementation):

- **Identitaet.** `app_user` (V1__baseline.sql:18) traegt nur
  `id, email, password_hash, display_name, created_at` — **keine globale Rolle**.
  `User` (server/.../identity/User.java) ist setterlos, wird ueber
  `User.create(email, argon2Hash, displayName)` angelegt.
- **Rollenmodell.** Die einzige Rolle ist org-weit: `MembershipRole {OWNER, MEMBER}`
  (server/.../org/MembershipRole.java) auf der Tabelle `membership(org_id, user_id, role)`.
  ADR-0004 begruendet explizit: Rollenentscheidungen lesen die Tabelle fresh pro
  Request (kein Claim im Session/Token), damit Entzug sofort wirkt.
- **Session-Gate.** `SessionAuthInterceptor` (Handler-Interceptor, kein Filter)
  prueft pro Request: (1) Session traegt `AuthPrincipal(userId, displayName)`
  sonst 401 generisch; (2) CSRF Double-Submit fuer aendernde Methoden sonst
  403 `CSRF_INVALID`. Geschuetzte Pfade sind eine **explizite Allowlist** in
  `SessionAuthConfig`: `/api/v1/orgs`, `/api/v1/orgs/**`, `/api/v1/gateway/**`,
  `/api/v1/invites`, `/api/v1/invites/**`. Neuflaechen muissen dort deliberate
  eingetragen werden — genau der Haken, an den der Admin-Guard ansetzt.
- **Session-Inhalt.** `AuthPrincipal` ist ein `record(Long userId, String displayName)`
  und bewusst nur ein Login-Nachweis, keine Autorisierungsquelle (ADR-0004:
  "The session stores ONLY the stable AuthPrincipal(userId); every authorization
  decision reads the [rollen] table fresh"). Ein ADMIN-Flag in die Session zu
  schreiben waere gegen dieses etablierte Prinzip (Revocation wirke erst zum
  naechsten Login).
- **Account-Bootstrap.** Prod hat keine Registrationsflaeche. Existsierender
  Bootstrap-Pfad: `deploy/tools/AccountTool.java` (JDBC direkt gegen Prod-PG,
  Argon2-Hash, idempotent, Passwort nie argv) plus `DevAccountSeeder`
  (nur dev-Profil, nur mit gesetzten `keplura.dev.seed.*`). Ein Bootstrap-Admin
  ueber .env ist eine neue, analoge Variante — der Seeder zeigt das Muster
  (profile-gated, idempotent, kein Credential-Reset bei Neustart).

**Ergebnis / wo die globale Admin-Rolle hin muss:**

Neue Spalte `app_user.role` (`character varying(20) NOT NULL DEFAULT 'USER'`,
Enum `UserRole {USER, ADMIN}`) — global, org-unabhaengig, komplementaer zu
MembershipRole (die bleibt fuer Tenant-Autorisierung unveraendert). Die
Entscheidung "ist das ein Admin" wird — wie bei ADR-0004 fuer Memberships —
pro Request fresh aus der DB gelesen, nicht in die Session kopiert:

- Ein zweiter Interceptor `AdminGuardInterceptor` wird in `SessionAuthConfig`
  auf `/api/v1/admin/**` registriert **nach** dem Session/CSRF-Gate
  (Interceptor-Reihenfolge: Auth zuerst, dann Rolle). Pruefung:
  `userRepository.findById(principal.userId()).role == ADMIN`, sonst 403 mit
  generischem Body (gleiche Form wie die bestehenden Gates — kein Erraten von
  "existiert der User noch"). Damit gilt die PO-Regel "serverseitige
  Rechtepruefung PFLICHT": Die Desktop-Client-Logik (Menuepunkt nur fuer ADMIN)
  ist rein kosmetisch; die Wire-Schicht antwortet 403 unabhaengig davon.
- Auth-Grundarchitektur bleibt unangetastet: Sessions, Cookie-Posture, CSRF,
  Tenant-Modell, Login-Flow, 401/403-Bodies — alles gleich. Kein PO-Gate
  noetig (SCOPE A der PO-Spec bestaettigt das: "KEINE Aenderung der
  Auth-Grundarchitektur").
- **Bootstrap-Admin bleibt .env** (PO-Vorgabe): neuer `AdminBootstrapRunner`
  nach dem Muster von DevAccountSeeder — aktiv **in jedem Profil**, aber nur
  wenn `keplura.admin.bootstrap.email` (plus Passwortquelle) gesetzt ist;
  idempotent: existiert der User, wird nur seine Rolle auf ADMIN gehoben, das
  Passwort niemals zurueckgesetzt (kein Backdoor wie beim Seed-Rule schon
  diskutiert); loggt nur "bootstrap admin role applied", nie die Adresse.
  Ohne gesetzte Properties passiert nichts — out-of-the-box existiert kein
  Credential. Alternative fuer Profis: `AccountTool`-Extension `promote-admin`.
- Der CSRF-Gate gilt automatisch auch fuer `/api/v1/admin/**`, sobald der Pfad
  in `SessionAuthConfig` steht — Admin-POSTs brauchen also den bestehenden
  Double-Submit-Token; keine neue CSRF-Flaeche.

## 2. Inventar: aktuelle Provider-/Modell-Konfiguration (DB-Umstellungskandidaten)

Single source of truth ist `server/src/main/resources/application.properties`
(ENV-Overrides in `deploy/.env.production.example`, Preset-Defaults in
`gateway/ProviderPreset.java`, Chat-Aufloesung in `chat/ChatProperties.of`).

| # | Property (ENV) | Default / Preset-Werte | Konsument | Ziel nach ADMIN-KI |
|---|----------------|------------------------|-----------|--------------------|
| 1 | `keplura.provider` (KEPLURA_PROVIDER) | ollama \| vllm \| api \| "" (raw M4a-Pfad; unbekannt => Boot fails) | GatewayConfiguration, ChatProperties | Provider-Zeile (Typ) + aktiver-Chat-Provider-Zeiger |
| 2 | `keplura.gateway.base-url` (KEPLURA_GATEWAY_BASE_URL) | Preset: `http://127.0.0.1:11434/v1` / `http://127.0.0.1:8000/v1` / `https://api.openai.com/v1` | GatewayClient | Provider.base_url |
| 3 | `keplura.gateway.api-key` (KEPLURA_GATEWAY_API_KEY) | "" (nie loggen/leaken; AuthKind pro Preset) | GatewayClient | Provider.secret (verschluesselt) |
| 4 | `keplura.gateway.allowed-models` (KEPLURA_GATEWAY_ALLOWED_MODELS) | "" = any | GatewayClient-Validator | Modell-Zeilen (aktiv/inaktiv ersetzt Allowlist) |
| 5 | `keplura.gateway.timeout-seconds` (…_TIMEOUT_SECONDS) | 30 | GatewayConfiguration | Provider.timeout |
| 6 | `keplura.chat.model` (KEPLURA_CHAT_MODEL) | "" => Preset-Default (`qwen2.5:0.5b` / `default` / `gpt-4o-mini`), raw: `local-chat-dev` | ChatService | Modell-Zeile CHAT + Standardmodell-Flag |
| 7 | `keplura.chat.max-tokens` (…_MAX_TOKENS) | 0 = provider-decides (Ollama-Clamp 1024) | ChatProperties | Modell.max_output_tokens (optional) |
| 8 | `keplura.search.embedding-backend` (KEPLURA_EMBEDDING_BACKEND) | `local` \| `gateway` | SearchConfiguration/EmbeddingService | Typ-EMBEDDING-Modell + aktiv-Zeiger (backend lokal = impliziter Fallback, bleibt erhalten) |
| 9 | `keplura.search.embedding-model` (…_MODEL) | `local-hash-64` | GatewayEmbeddingService | Modell.technical_model_id (CHAT|EMBEDDING) |
| 10 | `keplura.search.embedding-base-url` (…_BASE_URL) | "" | GatewayEmbeddingService | Provider.base_url (zweiter Provider-Eintrag!) |
| 11 | `keplura.search.embedding-api-key` (…_API_KEY) | "" | GatewayEmbeddingService | Provider.secret |
| 12 | `keplura.search.embedding-dim` (…_DIM) | 64 (muss Modell-Dim treffen; pgvector-Spalte!) | Embedding/PgVectorStore | Modell.embedding_dim (Aenderung => Index-Konsequenz, Scope D) |
| 13 | `keplura.search.embedding-timeout-seconds` | 30 | GatewayEmbeddingService | Provider.timeout |
| 14 | `keplura.chat.context-messages` / `rag-top-k` / `max-prompt-chars` / `context-chunk-chars` | 20 / 4 / 4000 / 700 | ChatService | **Scope D "anzeigen, wo sicher aendern"** — erste Runde read-only anzeigen (app-Properties, nicht providerbezogen) |
| 15 | `keplura.search.chunk-size-chars` / `chunk-overlap-chars` | 800 / 100 (Boot-validiert: size>=overlap, size<=4000) | DocumentChunker | wie 14: anzeigen; Aenderung nur mit validierter Regel + Index-Hinweis |

**Klar getrennt — bleibt in .env (PO-Grenze "GRENZEN"):**

- DB: `KEPLURA_DB_URL/USER/PASSWORD/DRIVER`, `POSTGRES_*`, `KEPLURA_DB_DDL_AUTO`
- **Neu:** Master-Key fuer Secret-Verschluesselung (`KEPLURA_SECRETS_MASTER_KEY`, nur .env)
- **Neu:** Bootstrap-Admin (`keplura.admin.bootstrap.*`)
- Ports/Host: `KEPLURA_SERVER_HOST_PORT`, `server.port`, `SPRING_PROFILES_ACTIVE`
- Uploads/Parsing/Stores: `KEPLURA_UPLOAD_DIR`, `KEPLURA_UPLOAD_MAX_BYTES`,
  `KEPLURA_PARSER_*`, `KEPLURA_SEARCH_STORE` (Infra-Entscheidungen, keine
  KI-Provider-Werte; Such-Bounds 14/15 bleiben app-Properties mit DB-Oberbau spaeter)
- Preset-Defaults bleiben als Seed-Quelle erhalten (Nummern 1–13 = "Seed/Fallback",
  siehe 5. Uebergangsstrategie).

## 3. Datenmodell (Flyway V9+, Nummern bis Merge-Basis frei halten)

```
V9__admin_ki_config.sql (portable, V1-Stil: keine FKs, benannte Constraints)

app_user
  + role varchar(20) NOT NULL DEFAULT 'USER'      (USER | ADMIN)

ai_provider
  id            bigint identity PK
  name          varchar(200)  NOT NULL            (Anzeigename)
  provider_type varchar(20)   NOT NULL            (ollama | vllm | openai-kompatibel
                                                   -> wiederverwendet ProviderPreset-Id-Vokabular)
  base_url      varchar(500)  NOT NULL
  secret_cipher varchar(1000) NULL                (AES-GCM, Basis64; NIE Klartext)
  timeout_seconds integer     NOT NULL DEFAULT 30
  enabled       boolean       NOT NULL DEFAULT TRUE
  created_at / updated_at     timestamp(6) tz NOT NULL
  CONSTRAINT ai_provider_pkey PRIMARY KEY (id)

ai_model
  id                bigint identity PK
  provider_id       bigint       NOT NULL
  name              varchar(200) NOT NULL
  technical_model_id varchar(300) NOT NULL        (upstream-Model-Name)
  model_type        varchar(20)  NOT NULL         (CHAT | EMBEDDING)
  embedding_dim     integer      NULL             (nur EMBEDDING; pgvector-Kopplung!)
  max_output_tokens integer      NULL
  temperature       numeric(3,2) NULL
  enabled           boolean      NOT NULL DEFAULT TRUE
  is_default        boolean      NOT NULL DEFAULT FALSE  (s. Konsistenzregeln unten)
  created_at / updated_at
  CONSTRAINT ai_model_pkey PRIMARY KEY (id)
```

Konsistenzregeln (genau ein `is_default=true` pro `model_type` unter `enabled=true`;
ein PG-Partial-Index waere nicht H2-portabel — erzwungen wird das transaktional
im Service und DB-seitig z.B. ueber eine eindeutige, berechnete
`default_slot`-Spalte oder aequivalent, Detail beim Implementieren):
ein Default darf nur auf einem enabled-Provider zeigen. Provider-Loeschen ist nicht
vorgesehen (PO-Funktionen: anlegen/bearbeiten/deaktivieren/testen/status) —
`enabled=FALSE` ist das Deaktivieren; deaktivierter Provider wird nicht
aufgeloest (Chat/Embedding-Pfad filtert `enabled`).

Warum keine `org_id`: Provider/Modelle sind eine **globale Deployment-Ressource**
(ein Backend pro Installation, wie heute .env), nicht tenant-Sache — Org-Scoping
waere neuer Scope ohne PO-Wunsch.

## 4. Secrets: Verschluesselung + Leak-Disziplin

- `SecretCipher`-Service: AES-256-GCM, Key = SHA-256 des `KEPLURA_SECRETS_MASTER_KEY`
  ENV-Werts (.env-Bleiber; prod ohne gesetzten Master-Key => Admin-Secret-Features
  deaktiviert mit klarem Status, Boot stirbt NICHT — Startup-Regel unten).
  Zufalls-IV pro encryption, IV praefixiert im Ciphertext, Basis64 in
  `secret_cipher`. Master-Key fehlt => schreiben verweigert 503 `MASTER_KEY_MISSING`,
  lesen des Klartexts sowieso nie.
- **API-Vertrag**: Response-DTOs enthalten nur `secretSet: boolean` +
  `secretMasked: "****"` (nie Tail-Bytes des Klartexts). PATCH mit leerem
  `secret` = alten Wert behalten; non-leer = ersetzen (PO-Spec Scope B exakt).
- **Log-Disziplin wie bisher**: nur Provider-Name/id, nie URL mit Credentials,
  nie Ciphertext. `SystemInfoController.modelState()` bleibt die einzige
  oeffentliche Projektion (Vokabular none|local|configured) — gefuellt aus der
  DB-Konfiguration statt ENV.
- Master-Key-Rotation ist Out-of-Scope erste Runde (kein PO-Wunsch); Ciphertext
  traegt einen Key-Id-Praeifx-freien Ansatz "ein aktiver Key", dokumentierte
  Folgekarte moeglich.

## 5. Uebergangsstrategie .env -> DB (Nichtzerstoerung)

1. **Seed-Fallback**: Startet ohne DB-Zeilen (fresh Migration), wird die
   effektive Runtime-Konfiguration weiterhin aus den heutigen ENV/Properties
   gebaut (Klasse `GatewayProperties` bleibt der Resolved-Config-Typ — der
   Chat-/Gateway-/Embedding-Pfad aendert seine Signatur nicht).
2. **DB-Vorrang**: Existieren enabled-Zeilen (Provider mit Default-CHAT-Modell),
   baut ein `RuntimeProviderConfigResolver` daraus eine neue
   `GatewayProperties`-Instanz und ersetzt die ENV-Resolution fuer den
   Request-Pfad. Embedding analog: enabled-EMBEDDING-Default ueberschreibt
   `keplura.search.embedding-*`-Werte; sonst bleibt der lokale Hash-Backend.
3. **Explizites Seed-Endpunkt-Angebot statt automatischem Diebstahl**: Beim
   Merge-Release liefert der Admin-Bereich "Aus .env-Vorgaben anlegen" (ein
   Klick, idempotent, nur wenn DB leer) — so wird die aktuelle ENV nicht
   heimlich kopiert, sondern vom Admin bestaetigt. Warum nicht automatisch
   beim Start migrieren: ein ENV-Tipp wuerde sonst dauerhaft in der DB
   zementiert (Nichtzerstoerung + Reversibilität).
4. **Rueckfall**: Alle DB-Zeilen deaktivieren => System verhaelt sich exakt wie
   heute (ENV-Pfad). Keine Datenmigration noetig zum Zurueck.
5. `.env`-Werte bleiben lesbar fuer den Systemstatus ("Seed-Vorschlag"), aber
   NIE im Admin-API-Response mit Secret.

## 6. Admin-API + Guard-Design

- Prefix `/api/v1/admin/**` (neu in `SessionAuthConfig`-Allowlist aufnehmen =>
  Session + CSRF gelten automatisch; der `AdminGuardInterceptor` prueft die
  Rolle fresh pro Request, 403 generisch wie beim CSRF-Body).
- Endpunkte (openapi.yaml ergaenzen, PO-Grenze "API-Vertraege"):
  - `GET/POST /api/v1/admin/providers`, `PATCH /api/v1/admin/providers/{id}`
    (name/type/base_url/timeout/enabled; secret nur write-only),
    `POST /api/v1/admin/providers/{id}/test` (Verbindungstest, siehe 7)
  - `GET/POST /api/v1/admin/models`, `PATCH /api/v1/admin/models/{id}`,
    `PUT /api/v1/admin/models/{id}/default` (Default pro Typ atomar: Service
    transaktional)
  - `GET /api/v1/admin/system-status` — Backend/DB/Provider/Chat-Default/
    Embedding-Default je `ok|unreachable|not_configured|error`, boolesch
    projektion OHNE URL/Secret/Modell-Interna (Scope E).
  - `GET /api/v1/admin/rag-settings` — Scope D Chunking/RAG-Parameter
    (erste Runde read-only anzeigen + Hinweis bei Embedding-Wechsel:
    "Neuindexierung nicht automatisch ausgelöst").
- Fehlerpfade nutzen das bestehende `GatewayErrorAdvice`-Muster: generischer
  Code, nie Upstream-Body.

## 7. SSRF-Absicherung Verbindungstest

Der Verbindungstest dials eine Admin-eingegebene Base-URL serverseitig —
klassischer SSRF-Vektor. Pflichten (Security-Gate der PO-Spec):

1. Schema-Whitelist https + http (http nur fuer LAN-Ollama/vLLM, wie heute
   Preset-Defaults selbst 127.0.0.1 nutzen); URL muss absolut sein, ohne
   Userinfo.
2. Hostaufloesung PRUEFEN statt blind dialen: alle aufgeloesten IPs muessen
   globale Routen sein ODER in einer Ops-Whitelist
   (`keplura.admin.ssrf-allow-cidrs`, ENV, Standard: leer => loopback/RFC1918
   dicht). Redirects folgen: NIE (HttpClient `Redirect.NEVER` — uebernehmen
   vom bestehenden gatewayHttpClient).
3. Secrets reisen nicht mit: Test sendet nur das Secret des **geladenen**
   Providers (nie ein Request-Feld), kein Credential im Redirect (Redirect
   verboten, Punkt 2).
4. Timeout hart (Provider.timeout Sekunden, Cap), Response-Body-Cap klein
   (z.B. 64 KiB), Fehler antwortet nur `{"reachable":false,"code":"..."}` —
   nie die interne IP/Port-Diagnose zurueckgeben (Recon-Schutz).
5. Der eigentliche Chat-/Embedding-Verkehr laeuft unveraendert ueber den
   bestehenden GatewayClient (der dialed sowieso admin-verwaltete URLs — das
   SSRF-Risiko ist durch die Admin-Rolle selbst schon eingegrenzt; die
   obigen Regeln gelten fuer den NEUEN, von unten-validierten Eingabeweg
   "test before save").

## 8. Startup ohne Provider (PO-Pflicht)

- Backend bootet ohne jegliche Provider-Zeile UND ohne ENV-Provider:
  `GatewayProperties.configured()==false` existiert bereits als Zustand
  (Chat => 503, `/gateway/status` => configured=false). Der DB-Resolver
  produziert denselben leeren Zustand; nichts Neues zu bauen, nur die
  Existenzpruefung umziehen.
- Admin-Anmeldung funktioniert ohne Provider zwingend (Bootstrap-Admin braucht
  nur DB), Admin-Bereich erreichbar, KI-Karten zeigen "nicht konfiguriert".
- KEIN stiller Fallback auf externe Provider: der Seed-Knopf (5.) ist explizit
  und loggt seine Wirkung; die Boot-Validierung "unbekannter Providername =>
  Fail" bleibt.

## 9. Migrationen (V9+, Nummern frei halten)

- `V9__user_role.sql` — nur `app_user.role` (SCOPE A, klein + unabhaengig mergebar).
- `V10__ai_provider_model.sql` — die zwei Tabellen aus 3.
- Getrennte Dateien, damit SCOPE A auch bei SCOPE B/C-Aufspaltung stabil bleibt.
  Nummern gegen Merge-Basis pruefen (andere Zweige koennen V9 beanspruchen:
  Stand 08.09. ist V8 das Letzte auf main UND auf docs-state PR #35 — belegt
  durch `find db/migration`; bei Kollision beim Rebase hoehen).
- H2-Trakt: portable SQL wie V1-V8 (ddl-auto=validate laeuft gegen beide; die
  H2-CI-Replay-Tests in `ci-local.sh` muessen beide Files sehen).

## 10. Testplan-Mapping (PO-Mindestliste -> Gate)

| PO-Testanforderung | Testort |
|---|---|
| normaler User 403 auf Admin-API (Wire) | neuer `AdminGuardIntegrationTest` (mvn, MockMvc: USER => 403, ADMIN => 200, anonym => 401) |
| Admin-Menue nur fuer ADMIN (UI-Leg) | `admin_flow_e2e.cjs` (desktop/e2e, Playwright-Core wie invite_flow): Menue sichtbar/unsichtbar |
| Provider anlegen/aendern/deaktivieren | `AdminProviderCrudIntegrationTest` + E2E-Leg |
| Secret nie zurueckgegeben; leer behaelt alt / neu ersetzt | Crud-Test assertet Response-JSON ohne `secret`-Feld + Roundtrip Ciphertext != Klartext |
| deaktivierter Provider wird nicht genutzt | Resolver-Unit-Test: enabled=false => leerer Runtime-Config |
| Default-Modell korrekt gewaehlt | Resolver-Unit + Chat-Integration gegen Stub-Upstream |
| Backend-Start ohne Provider | bestehender Boot-Test mit leerer DB (`configured=false`) reicht, erweitern auf DB-Resolver |
| Verbindungstest-Fehler behandelt sauber | SSRF-Unit (CIDR-Block, Userinfo, Schema) + Integration gegen lokalen Fake (unreachable => generisch) |
| bestehende User-Flows gruen | ci-local.sh komplett (48 Klassen exactly-once, knowledge 14/14, auth, invite 19/19) |
| Security-Gate PFLICHT | reviewer + security-Karten nach PR-Oeffnung (Prozess ADMIN-KI Schritt 7) |

## Consequences

- Positiv: KI-Provider wartbar ohne Deploy; .env schrumpft auf Infra + Master-Key
  + Bootstrap; Rollenfrage bleibt DB-autoritativ (ADR-0004-Prinzip); Rueckfall
  auf ENV ist jederzeit moeglich.
- Risiko: `app_user.role`-Spalte = Schema-Aenderung, Hibernate-validate prueft
  sie — deshalb Migration vor Entity-Merge (V9 eigenstaendig). Master-Key-
  Verlust = Secrets unbrauchbar (Klartext nirgends); dokumentierte Ops-Regel:
  Master-Key ins Backup-Set. SSRF-Whitelist per ENV kann von Ops falsch weit
  gestellt werden — deshalb Default dicht und Security-Review Pflichttor.
- Out-of-Scope dieses ADR: Multi-User-Rollenverwaltung per Admin-UI (Scope
  Invite exists), Provider-Load-Balancing, Per-Org-Provider, Key-Rotation,
  automatische Neuindexierung bei Embedding-Wechsel.
