# Keplura Roadmap (living document)

Live-Status: **docs/PROJECT_STATE.md** (autoritative Projekt-Wahrheit: Stand,
nächste Schritte, PO-Entscheide, Blocker — diese Datei hält die Detail-Specs).

Source of truth for the active pipeline. The orchestrator (default) maintains
this file via branch + draft PR; merges follow the hard-gate policy in
AGENTS.md (auto squash-merge when gates are met, otherwise owner). "Approved" =
owner has said yes (Discord or master directive). "Idea" = backlog, never
auto-built.

**Priority invariant: the core MVP ships first —
Login → Organisation → Space → Dokument → RAG → Chat mit Quellen.
M3 and M4 outrank everything below; nothing under "IDEA / NOT APPROVED" may
be started, decomposed or tasked without explicit PO approval.**

## Aktuelle Priorität — Tool Use (PO-beschlossen 21.11.2026)

**Arbeitsziel für die nächsten Tage: Kepluras Tool Use mindestens auf den dokumentierten Funktionsumfang von Open WebUI bringen.** Verbindliche Reihenfolge und Abnahmeziele: [TOOL_USE_PLAN.md](TOOL_USE_PLAN.md). TU-01, TU-02 und TU-03a sind abgeschlossen (PRs #15/#17/#19, Merges 1296cdb/8f6852a/5c25bfa). Nächste einzelne Implementierungsaufgabe: [TU-03b](tasks/TU-03b-confirmed-continuation.md), begrenzte Fortsetzung nach konkreter Bestätigung als zweiter Teil von TU-03, geplant und noch nicht gestartet.

Reihenfolge: gemeinsame Grundlage → Bestätigungen/Aufrufanzeige → mehrstufige Ausführung → Wissen/Anhänge/Web → Auswahl/Tool-Suche/Rückfragen → Adapter/OAuth/Medien → zusätzliche Fähigkeiten einzeln. Eine aktive Implementierung; bestehende Sicherheitsbefunde und Gates bleiben verbindlich. Kein Fertigstellungstermin für den Gesamtumfang zugesagt.

Dieser explizite Auftrag ersetzt ältere allgemeine Folgeprioritäten. Er genehmigt nicht pauschal die unten weiterhin als IDEA geführten Meilensteine M5–M9. Grenzen und weiterhin nötige PO-Entscheidungen stehen im Plan.

## M0 — Foundation (completed 2026-09-03, CI included)

Minimal verifiable vertical slice: desktop + server exist, build, test, talk.

- [x] Repo structure (desktop/, server/, contracts/, deploy/, docs/) — merged via PR #2 (2ffec4b)
- [x] Server: Spring Boot 4.1.x, Java 21, `GET /api/v1/system/info` (product, serverVersion, apiVersion) — merged via PR #2
- [x] Desktop: Tauri 2 + Vue 3 + TS, configurable server URL, connection status
      (Connected / Not reachable) + versions from `/api/v1/system/info` — merged via PR #2
- [x] Real builds + tests on this host; Desktop→Server communication verified
      (evidence recorded in-repo, commit 1efbdad)
- [x] Draft PR + reviewer gate — PR #1 (bd2d091, governance) and PR #2
      (2ffec4b, foundation) reviewed and merged
- [x] CI task: GitHub Actions (server mvn verify + desktop npm build on
      ubuntu/windows/macos, cargo check on Linux) — merged via PR #3 (2234593),
      green on PRs. NOTE (PO decision 2026-09-06): Actions is no longer the
      binding merge gate — see the local CI gate line under M1.

## M1 — Server base & project rails (completed 2026-09-03)

- [x] Health endpoint, config profiles, logging conventions — merged via PRs
      for commits 0b17a2c (health) and 4e23a40 (dev/prod profiles)
- [x] CI pipeline green on PRs — GitHub Actions (from M0; green runs through
      early Sept 2026). SUPERSEDED by the PO decision 2026-09-06: the binding
      gate is now the reproducible LOCAL run `scripts/ci-local.sh` (stages:
      chunked mvn test manifest, desktop build+typecheck, cargo check, e2e
      suites) with committed evidence under `ci/evidence/<timestamp>/`; GitHub
      Actions is optional supplementary evidence whose billing failures never
      block a merge. The former "[CI billing exception documented]" PR
      phrasing is retired — PRs reference their ci-local evidence instead.
      See docs/development.md "Local CI gate (verbindlich)".
- [x] Small integration-test harness for the API — merged via commit 38d532f

## M2 — Identity & tenancy (approved; core complete, open tail items below)

PO decision 2026-09-03 (option A): local identity first, OIDC as a second login
path in its own later milestone — see docs/adr/0002.

- [x] M2a: User entity + Argon2 password hashing, no login endpoint — merged
      via commit 6b22f86
- [x] M2b: login endpoint / sessions — merged via commit ac9e6cd (ADR-0003)
- [x] M2c: Organization + Membership, session-gated org API, tenant isolation
      (ADR-0004) — merged via commit cae2d80
- [x] M2d: desktop login flow (Vue form + session handling against
      /api/v1/auth, org list, logout) — merged via commit d19a170
- [x] M2e: desktop org-creation form + role badges, CORS POST orgs +
      preflight fix — merged via commit 3d29d7f
- [x] M2-POLISH (pre-packaging): security gates F2+F4 + QA Retry-After —
      packaged origins on the CORS allow-list, exposed Retry-After, and the
      native HTTP transport closing the tauri:// SameSite limitation
      (PO decision Option B, ADR-0005)
- [x] CSRF token guard on state-changing session endpoints — SEC-2:
      double-submit cookie (`keplura_csrf`) + session-bound token,
      timing-safe 403 `CSRF_INVALID`, documented exemptions (login,
      preflight, SSE GETs) — closes the ADR-0003 deferral (SameSite=Lax
      stays as defence-in-depth)
- [x] Login via OIDC (auth code + PKCE) — komplett 09.09. (Merge d4b04a0,
      PR #44, alle Gates gruen; ADR-0009; nbf-Pflege nachgezogen t_d8fdaf51)
- [x] Invites (org member onboarding): OWNER-mints one-time 7-day hashed
      token → any existing user accepts as MEMBER (SEC-2, V8, MembersPanel);
      roles beyond OWNER/MEMBER remain open (tracked here, needs PO scope OK)

## M3 — Spaces & Knowledge (COMPLETED 2026-09-05)

- [x] M3a: PostgreSQL + pgvector foundation — compose stack (no secrets),
      Flyway baseline, prod profile on Postgres with ddl validate, H2 stays
      dev/test — merged via commit 3dce57d
- [x] M3b: Spaces (per-organization) + membership scoping — merged via commit
      25b5bbe (query-level isolation, security gate approved)
- [x] M3c: Knowledge bases + document upload/list/streaming download with
      3-level isolation — merged via commit 8804e70; ingestion/parse remains M3d
- [x] M3d: Embeddings + pgvector usage + RAG query path with source citation +
      upload hardening — merged via commit 4397385 (reviewer round-2 +
      security gate on final SHA; chunking 800/100, 4-pin tenant isolation)
- [x] M3e: Desktop UI for knowledge bases/documents (KB list/create, upload
      with honest indexStatus, per-KB search with full citations) — merged
      via commit ec628cd (reviewer + QA + security gates on final SHA)
- Object storage decision pending before M3c if files outgrow the local disk
  (owner-approved direction: suitable self-hosted object storage, not SaaS).

## M4 — Chat & Model Gateway (COMPLETED 2026-09-06)

Designrichtung Soft Structure · Apple Refined vom PO freigegeben 2026-09-05 —
UI-Redesign-Tasks referenzieren docs/DESIGN.md (UI-1 merged 032d3a0, UI-3
knowledge views merged 68e0c39; chat UI shipped with M4b).

- [x] OpenAI-compatible model gateway, server-side (API keys never reach the
      desktop) — merged via commit 992a86c (M4a; reviewer + security gates;
      QA streaming legs deferred to and covered by M4b)
- [x] Chat UI with SSE streaming, persisted conversations, RAG citations
      (Apple-Refined design) — merged via commit 8b5eeda (M4b; reviewer +
      security + QA gates on final SHA; reviewer caught+fixed OOM body-read
      finding F-1)
- [x] vLLM and/or Ollama hookup — merged via commit f726cc0 (M4c): the
      hookup itself is resolved — Ollama, vLLM and hosted
      OpenAI-compatible APIs are all first-class presets
      (`KEPLURA_PROVIDER=ollama|vllm|api`, keys stay server-side only).
      See `docs/development.md` — "Modell-Provider". Hardware/hosting per
      backend remains a PO decision (this host's 4 GB RAM is insufficient
      for serious vLLM models).

## ─────────────────────────────────────────────
## LONG-TERM PRODUCT VISION — IDEA / NOT APPROVED
## ─────────────────────────────────────────────

Status of EVERYTHING below this line: **IDEA / NOT APPROVED**. Recorded per PO
directive of 2026-09-04 as documentation only. No tasks may be created from
these sections, no code written, until the PO explicitly approves a milestone
AND the core MVP (M3+M4) is shipped.

Vision: Keplura grows from a private enterprise AI chat/RAG platform into the
company's secure AI **work platform**. Company systems attach via simple,
admin-friendly **Integrations** (examples: Zammad, Wiki.js, GitHub,
fileserver/SMB, ERP, CRM, further internal/external systems). A normal
administrator needs **zero** knowledge of MCP, tool schemas, JSON-RPC or REST
implementation details. Product principle: **"Integration" is the product;
MCP is just one possible technical implementation underneath.**

### Binding glossary (verbindlich)

1. **Integration** — a concrete connection of the company to one system
   (e.g. "Zammad Produktion").
2. **Connector** — the implementation linking Keplura to that system
   (e.g. "Zammad Connector").
3. **Tool / Action** — one technically executable function
   (e.g. `search_tickets`, `get_ticket`, `add_internal_note`,
   `change_ticket_status`, `delete_ticket`).
4. **Capability** — an admin-readable grouping
   (e.g. `tickets.search`, `tickets.read`, `tickets.comment`,
   `tickets.modify`, `tickets.delete`).
5. **Adapter / Runtime** — the technical transport
   (MCP, REST, SMB/filesystem, Webhook, future protocols).

The Keplura core must NEVER depend directly on MCP. A connector may use MCP
internally but does not have to.

Every connector registers concrete tools/actions and maps them onto
capabilities (e.g. `search_tickets → tickets.search`). Capabilities exist for
the admin UI; **authorization is still enforced at TOOL/ACTION level**. A
connector never automatically gets all its tools. Tools newly appearing via a
connector update default to **DISABLED** until explicitly approved.

### Security model (for the whole M5–M9 range)

Every tool/action carries a risk class:

- **READ** — search/read/display/analyse, no side effects.
- **WRITE** — comments/notes, status changes, create/modify records.
- **CRITICAL** — delete, approvals, sending, bookings, purchases,
  user/permission changes, irreversible actions, especially sensitive reads.

Rules:
1. Default is **DENY** for everything.
2. READ: usable by the AI automatically **after explicit admin approval**.
3. WRITE: disabled by default; admin must activate explicitly.
4. CRITICAL: disabled by default; needs explicit admin approval **and**
   concrete user confirmation immediately before execution.
5. Keplura may tighten a tool's risk class; a connector may never downgrade
   its own class to bypass Keplura rules.
6. The LLM may never: extend its own rights, enable tools, change risk levels
   or alter policies.
7. An MCP server or other connector never decides its own permissions.
8. Keplura is always the central security and permission boundary.

### Permission scopes (later, minimum levels)

Organisation → Space → Integration → Capability → single Tool/Action
(e.g. ACME / IT / "Zammad": search ✓, read ✓, internal note ✓,
status change ✗, delete ✗). Roles/single-user scopes optional later.
Do not over-engineer the first integration MVP.

### Admin experience

Setup must look like: select integration → server URL → credentials → test
connection → detected capabilities shown → pick permissions → save → done.
LESEN/SCHREIBEN/KRITISCH grouped checkboxes, "Verbindung testen" with
plain-language result. Technical details optional for power admins, never a
prerequisite.

### Diagnose & health

Every integration has a human-readable health status (e.g. "Verbindung
gestört · letzter erfolgreicher Zugriff vor 18 Minuten · Authentifizierung
fehlgeschlagen · API-Token ungültig oder abgelaufen · [Verbindung erneut
testen]"). Normal admins must not read container logs or MCP transcripts for
typical problems.

### Credentials

Centrally controlled by Keplura: never to the LLM, never into prompts, never
fully into audit logs; a connector receives only the credentials it needs;
values are not re-displayed in full after saving; encrypted storage and
credential rotation required.

### Audit

Every tool call auditable: time, user, org, space, integration, tool/action,
risk level, model/agent, allowed/denied, user confirmation if required,
success/error. Sensitive parameters, credentials and payload noise are not
logged in full.

### Events

Connectors must eventually deliver events as well as tools (Zammad: new
ticket; GitHub: PR failed; ERP: machine fault; fileserver: new document).
Events and executable actions are separate concepts; an event must never
auto-create WRITE/CRITICAL rights.

## DS — Datenquellen mit Rechte-Spiegelung — APPROVED (PO 14.11.2026)

Spec: docs/adr/0012-datenquellen-und-rechte-spiegelung.md. Quellen: Windows-Freigabe (SMB), Linux-Verzeichnis
(NFS/POSIX-ACL), SharePoint Online. Identitäten: Active Directory, generisches LDAP (OpenLDAP/FreeIPA),
Entra ID. Pflicht: Einrichtung per Assistent für Sysadmins ohne KI-Wissen, Rechte-Filter in der
Datenbankabfrage (fail-closed), Quellen read-only. Reihenfolge: DS-1 RAG-Qualität → DS-2 Verzeichnis/LDAP →
DS-3 Linux + SMB → DS-4 SharePoint → DS-5 Qualitätsmessung.

## M5 — Keplura Connect — IDEA / NOT APPROVED

Foundation for secure company integrations: connector model, integration
registry, connector manifest, tool/action registry, capability mapping,
READ/WRITE/CRITICAL risk model, tool-level permission enforcement,
org/space scoping, credential management, health checks, audit logs, admin
setup wizard. Result: an admin adds, tests and safely approves an integration
without knowing connector internals.

## M6 — Connector runtimes — IDEA / NOT APPROVED

Multiple technical transports: MCP client runtime, REST connector runtime,
filesystem/SMB adapter, webhook/event input, timeouts, rate limits, tool
schema validation, failure isolation, connector health, safe tool invocation.
MCP stays an adapter, never a core dependency.

## M7 — First-party integrations — IDEA / NOT APPROVED

First officially maintained integrations: Zammad, Wiki.js, GitHub,
fileserver/SMB, ERP/internal systems — never all at once; each one gets its
own prioritisation only after later PO approval.

## M8 — Actions & automation — IDEA / NOT APPROVED

Long term: event → AI, rules, safe automations, approval workflows, WRITE
actions, CRITICAL actions with user confirmation. Example: new Zammad ticket →
analyse → search internal knowledge → propose solution — but never modify the
ticket, send messages or change status without matching permission.

## M9 — Integration ecosystem — IDEA / NOT APPROVED

Very late: external connector SDK, signed connector packages, versioning,
safe updates, third-party connectors, optional marketplace, optional
enterprise connector packs. Requires the stable first-party model of M5–M8
first.

## Central architectural principle

**„Keplura integriert Unternehmenssysteme über Connectoren.
Administratoren verwalten verständliche Fähigkeiten und Berechtigungen.
Die Durchsetzung erfolgt bis auf einzelne Tools/Actions.
MCP, REST, SMB, Webhooks und zukünftige Protokolle sind austauschbare
technische Adapter unterhalb dieser Produktabstraktion."**

## Explicit non-goals (unchanged until owner approval)

Mobile apps, workflow engines, microservices, Kubernetes, Kafka, Redis,
speculative infrastructure. For the M5–M9 range additionally: no MCP runtime,
no connector marketplace, no agent platform, no automation engine, no plugin
SDK, no Zammad/ERP connector, no third-party modules — all of it stays IDEA
until the PO approves it after the core MVP (M3+M4) is finished.
