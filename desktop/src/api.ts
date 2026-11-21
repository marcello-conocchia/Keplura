// Desktop ↔ Server API calls (M2d — login flow, extended by M2e/M3e).
//
// Every call goes to the configured server base URL (see .env VITE_SERVER_URL)
// through the native transport layer (desktop/src/transport.ts, ADR-0005):
// inside a Tauri webview the request runs on the Rust HTTP client so the
// HttpOnly SameSite=Lax session cookie works in the packaged app; in a plain
// browser (dev/QA flow) it falls back to webview fetch with
// credentials: "include". The server-side CORS policy (DesktopCorsConfig)
// allows exactly the webview origins with credentials.

import { desktopFetch, serverOrigin, setCsrfToken } from "./transport";
import type { ToolCallCard } from "./tool-cards";

/** Base URL of the Keplura Server API, configurable per environment. */
export const serverUrl = serverOrigin;

/** Shape of a successful POST /api/v1/auth/login (contracts/openapi.yaml). */
export interface AuthUser {
  userId: number;
  displayName: string;
  /**
   * SEC-2: session-bound CSRF token, identical to the keplura_csrf cookie
   * value. The native Tauri transport cannot read cookies, so the body is the
   * ONLY channel for it — login() captures it via setCsrfToken() and every
   * later state-changing call carries it as X-CSRF-Token (transport.ts).
   */
  csrfToken?: string;
  /**
   * ADMIN-KI global deployment role ("USER" | "ADMIN"). DISPLAY ONLY: the
   * menu entry reacts to it, every /api/v1/admin/** request is still gated
   * server-side (session + CSRF + role) — a forged value here authorises
   * nothing.
   */
  role?: string;
  /** PILOT 4e: account still uses an admin-issued start password. */
  mustChangePassword?: boolean;
}

/** Shape of GET /api/v1/auth/me (ADMIN-KI restore path). */
export interface MeProfile {
  userId: number;
  displayName: string;
  role: string;
  mustChangePassword?: boolean;
}

/** Shape of one entry from GET /api/v1/orgs. */
export interface Organization {
  id: number;
  name: string;
  role: string;
}

/**
 * Outcome of a login attempt. Deliberately coarse: the client never learns
 * WHY credentials were rejected (anti-enumeration contract of ADR-0003) and
 * the UI must not add any detail beyond these variants.
 */
export type LoginOutcome =
  | { kind: "ok"; user: AuthUser }
  | { kind: "invalid" }
  | { kind: "rate-limited"; retryAfterSeconds: number | null }
  | { kind: "offline" }
  | { kind: "server-error" };

/**
 * Outcome of GET /api/v1/orgs — errors are tolerated by the UI. "expired"
 * (server 401) means the session is gone; any other failure keeps the current
 * state untouched because it says nothing about the session.
 */
export type OrgsOutcome =
  | { kind: "ok"; orgs: Organization[] }
  | { kind: "expired" }
  | { kind: "error" };

/**
 * Outcome of POST /api/v1/orgs (M2e — org creation form). Deliberately as
 * coarse as the login outcome: 401 maps to "expired" (the store drops the
 * session), every other failure is a plain "error" so no server-side detail
 * (e.g. WHY a name was rejected beyond its copy) can leak into the UI.
 */
export type CreateOrgOutcome =
  | { kind: "created"; org: Organization }
  | { kind: "invalid-name" }
  | { kind: "expired" }
  | { kind: "rate-limited"; retryAfterSeconds: number | null }
  | { kind: "error" };

async function postNoBody(path: string): Promise<boolean> {
  try {
    const response = await desktopFetch(`${serverUrl}${path}`, {
      method: "POST",
      signal: AbortSignal.timeout(10000),
    });
    return response.ok;
  } catch {
    // Offline / timeout: the session is at worst left to expire server-side.
    return false;
  }
}

/** POST /api/v1/auth/login — sets the session cookie on success. */
export async function login(email: string, password: string, rememberMe = false): Promise<LoginOutcome> {
  let response: Response;
  try {
    response = await desktopFetch(`${serverUrl}/api/v1/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, password, rememberMe }),
      signal: AbortSignal.timeout(10000),
    });
  } catch {
    // Network failure or timeout — no HTTP status exists at all.
    return { kind: "offline" };
  }

  if (response.ok) {
    try {
      const user = (await response.json()) as AuthUser;
      // SEC-2: capture the token BEFORE the outcome is handed to the store —
      // if it is missing the server predates SEC-2; then clear any stale copy
      // so the transport never sends a token from an older session.
      setCsrfToken(user.csrfToken && user.csrfToken.length > 0 ? user.csrfToken : null);
      return { kind: "ok", user };
    } catch {
      return { kind: "server-error" };
    }
  }
  if (response.status === 401) {
    // Identical for unknown email and wrong password — keep it that way in the UI.
    return { kind: "invalid" };
  }
  if (response.status === 429) {
    const header = response.headers.get("Retry-After");
    const seconds = header !== null ? Number.parseInt(header, 10) : NaN;
    return {
      kind: "rate-limited",
      retryAfterSeconds: Number.isFinite(seconds) && seconds > 0 ? seconds : null,
    };
  }
  if (response.status === 400) {
    // Server-side validation of an input the UI already guards; surface the
    // same generic message as 401 so no extra information leaks.
    return { kind: "invalid" };
  }
  return { kind: "server-error" };
}

/** POST /api/v1/auth/logout — always best-effort; 204 or network error. */
export async function logout(): Promise<void> {
  await postNoBody("/api/v1/auth/logout");
  // SEC-2: whatever the outcome, the local token is dead with the session —
  // a stale copy would only poison the next login (cleared+re-set there).
  setCsrfToken(null);
}

/**
 * SEC-2 restore path: GET /api/v1/auth/csrf re-reads the token bound to a
 * RESTORED session (webview reload after app restart — the session cookie
 * lives in the native jar, but the in-page memory died with the old webview;
 * localStorage may hold a token from an OLDER, rotated session). 401/expired
 * is reported to the caller via ok:false so the store can drop the session.
 */
export async function refreshCsrfToken(): Promise<boolean> {
  try {
    const response = await desktopFetch(`${serverUrl}/api/v1/auth/csrf`, {
      signal: AbortSignal.timeout(10000),
    });
    if (!response.ok) {
      return false;
    }
    const body = (await response.json()) as { csrfToken?: string };
    if (!body.csrfToken || body.csrfToken.length === 0) {
      return false;
    }
    setCsrfToken(body.csrfToken);
    return true;
  } catch {
    return false;
  }
}

/**
 * ADMIN-KI restore path: GET /api/v1/auth/me — the profile (userId,
 * displayName, role) behind a RESTORED session. Called after a successful
 * liveness probe so a reloaded webview knows whether to show the admin
 * panel; role is display-only (server-side enforcement is independent).
 */
export async function fetchMe(): Promise<{ kind: "ok"; me: MeProfile } | { kind: "expired" } | { kind: "error" }> {
  try {
    const response = await desktopFetch(`${serverUrl}/api/v1/auth/me`, {
      signal: AbortSignal.timeout(10000),
    });
    if (response.status === 401) return { kind: "expired" };
    if (!response.ok) return { kind: "error" };
    return { kind: "ok", me: (await response.json()) as MeProfile };
  } catch {
    return { kind: "error" };
  }
}

/** GET /api/v1/orgs — membership-filtered list; 401/error tolerated by caller. */
export async function fetchOrgs(): Promise<OrgsOutcome> {
  try {
    const response = await desktopFetch(`${serverUrl}/api/v1/orgs`, {
      signal: AbortSignal.timeout(10000),
    });
    if (response.status === 401) {
      return { kind: "expired" };
    }
    if (!response.ok) {
      return { kind: "error" };
    }
    return { kind: "ok", orgs: (await response.json()) as Organization[] };
  } catch {
    return { kind: "error" };
  }
}

/** Server-side cap on the org name (Organization.create, docs/adr/0004). */
export const ORG_NAME_MAX_LENGTH = 200;

/**
 * POST /api/v1/orgs — create an organization; the caller becomes OWNER.
 * The session cookie rides along via desktopFetch like every other call; a
 * 401 means the session expired and the store reacts to that.
 */
export async function createOrg(name: string): Promise<CreateOrgOutcome> {
  let response: Response;
  try {
    response = await desktopFetch(`${serverUrl}/api/v1/orgs`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name }),
      signal: AbortSignal.timeout(10000),
    });
  } catch {
    // Network failure or timeout — no HTTP status exists at all.
    return { kind: "error" };
  }

  if (response.status === 201) {
    try {
      const org = (await response.json()) as Organization;
      return { kind: "created", org };
    } catch {
      return { kind: "error" };
    }
  }
  if (response.status === 400) {
    return { kind: "invalid-name" };
  }
  if (response.status === 401) {
    return { kind: "expired" };
  }
  if (response.status === 429) {
    // No org-path limiter exists today; this branch is what makes the
    // exposed Retry-After header meaningful should one be added (server
    // returns 429 only via the login limiter for now).
    const header = response.headers.get("Retry-After");
    const seconds = header !== null ? Number.parseInt(header, 10) : NaN;
    return {
      kind: "rate-limited",
      retryAfterSeconds: Number.isFinite(seconds) && seconds > 0 ? seconds : null,
    };
  }
  return { kind: "error" };
}

// ---------------------------------------------------------------------------
// M3e — knowledge UI: spaces, knowledge bases, documents, search (M3b/c/d
// APIs). Same conventions as the org calls above: every request rides on
// desktopFetch (native transport, ADR-0005), outcomes are deliberately coarse
// (401 -> "expired" drops the session store-side; 404 is the uniform
// chain-invisible answer of the server, so the client never learns WHICH
// level failed), and no server error body detail leaks into the UI.
// ---------------------------------------------------------------------------

/** Shape of one entry from GET /api/v1/orgs/{orgId}/spaces. */
export interface Space {
  id: number;
  orgId: number;
  name: string;
}

/** Shape of one entry from GET .../knowledge-bases. */
export interface KnowledgeBase {
  id: number;
  spaceId: number;
  name: string;
}

/**
 * Shape of one document row from GET/POST .../documents. indexStatus is the
 * server's honest per-document answer (M3d; real parsers since MVP-5):
 * INDEXED (searchable now), NO_TEXT_LAYER (pdf/docx without extractable
 * text, e.g. a scan) or NOT_INDEXED (legacy rows predating indexing).
 */
export interface KnowledgeDocument {
  id: number;
  kbId: number;
  filename: string;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  uploadedBy: number;
  indexStatus: "INDEXED" | "NO_TEXT_LAYER" | "NOT_INDEXED" | "PENDING" | "FAILED";
  /** ISO instant; absent on servers before the redesign. */
  createdAt?: string;
}

/** One search hit with full source attribution (contracts: SearchHit). */
export interface SearchHit {
  documentId: number;
  filename: string;
  chunkIdx: number;
  text: string;
  /** Cosine similarity in [-1, 1] — dev embedding model, NOT semantic (M3d). */
  score: number;
  pageNumber?: number | null;
  sectionTitle?: string | null;
  startOffset?: number | null;
  endOffset?: number | null;
  sourceUpdatedAt?: string | null;
}

/** Response body of POST .../search. */
export interface SearchResult {
  query: string;
  topK: number;
  backend: string;
  model: string;
  results: SearchHit[];
}

/** Server-side caps mirrored from the contracts (kept in sync deliberately). */
export const SPACE_NAME_MAX_LENGTH = 200;
export const KB_NAME_MAX_LENGTH = 200;
export const SEARCH_QUERY_MAX_LENGTH = 4000; // keplura.search.max-query-chars
/** DocumentUploadPolicy whitelist on the server; mirrored for the file picker. */
export const DOCUMENT_ACCEPT_EXTENSIONS = ["pdf", "txt", "md", "docx", "csv", "xlsx", "pptx"];

type ListOutcome<T> =
  | { kind: "ok"; items: T[] }
  | { kind: "expired" }
  | { kind: "error" };

/**
 * Shared GET for the nested list endpoints (spaces / kbs / documents):
 * 401 -> expired, everything else non-ok (incl. the uniform chain-404) -> a
 * plain error the UI shows as "konnte nicht geladen werden".
 */
async function getList<T>(path: string): Promise<ListOutcome<T>> {
  try {
    const response = await desktopFetch(`${serverUrl}${path}`, {
      signal: AbortSignal.timeout(10000),
    });
    if (response.status === 401) {
      return { kind: "expired" };
    }
    if (!response.ok) {
      return { kind: "error" };
    }
    return { kind: "ok", items: (await response.json()) as T[] };
  } catch {
    return { kind: "error" };
  }
}

/** GET /api/v1/orgs/{orgId}/spaces — membership-filtered list. */
export async function fetchSpaces(orgId: number): Promise<ListOutcome<Space>> {
  return getList<Space>(`/api/v1/orgs/${orgId}/spaces`);
}

/** GET .../knowledge-bases of one space. */
export async function fetchKnowledgeBases(
  orgId: number,
  spaceId: number,
): Promise<ListOutcome<KnowledgeBase>> {
  return getList<KnowledgeBase>(`/api/v1/orgs/${orgId}/spaces/${spaceId}/knowledge-bases`);
}

/** GET .../documents of one knowledge base (metadata incl. indexStatus). */
export async function fetchDocuments(
  orgId: number,
  spaceId: number,
  kbId: number,
): Promise<ListOutcome<KnowledgeDocument>> {
  return getList<KnowledgeDocument>(
    `/api/v1/orgs/${orgId}/spaces/${spaceId}/knowledge-bases/${kbId}/documents`,
  );
}

/**
 * Outcome of a nested POST that creates a row (space/kb). 409 = duplicate
 * name in the same parent (server contract), 404 = the parent chain is not
 * visible (also fires when an auto-selected parent went stale — the store
 * reloads then), everything else stays one generic error.
 */
export type CreateNestedOutcome<T> =
  | { kind: "created"; item: T }
  | { kind: "invalid-name" }
  | { kind: "duplicate-name" }
  | { kind: "parent-gone" }
  | { kind: "expired" }
  | { kind: "error" };

async function postCreateJson<T>(path: string, name: string): Promise<CreateNestedOutcome<T>> {
  let response: Response;
  try {
    response = await desktopFetch(`${serverUrl}${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name }),
      signal: AbortSignal.timeout(10000),
    });
  } catch {
    return { kind: "error" };
  }
  if (response.status === 201) {
    try {
      return { kind: "created", item: (await response.json()) as T };
    } catch {
      return { kind: "error" };
    }
  }
  if (response.status === 400) return { kind: "invalid-name" };
  if (response.status === 401) return { kind: "expired" };
  if (response.status === 404) return { kind: "parent-gone" };
  if (response.status === 409) return { kind: "duplicate-name" };
  return { kind: "error" };
}

/** POST .../spaces — org members may create; name unique per org. */
export async function createSpace(orgId: number, name: string): Promise<CreateNestedOutcome<Space>> {
  return postCreateJson<Space>(`/api/v1/orgs/${orgId}/spaces`, name);
}

/** POST .../knowledge-bases — name unique per space. */
export async function createKnowledgeBase(
  orgId: number,
  spaceId: number,
  name: string,
): Promise<CreateNestedOutcome<KnowledgeBase>> {
  return postCreateJson<KnowledgeBase>(
    `/api/v1/orgs/${orgId}/spaces/${spaceId}/knowledge-bases`,
    name,
  );
}

/**
 * Outcome of the multipart document upload. No client-side pre-validation
 * shortcuts: the server re-checks type/size/encoding and extraction
 * (MVP-5 parsers) and the UI shows the matching generic copy (400 type or
 * unparseable content, 413 size, 503 parser busy, 404 stale parent chain).
 */
export type UploadOutcome =
  | { kind: "uploaded"; document: KnowledgeDocument }
  | { kind: "rejected-type" }
  | { kind: "too-large" }
  | { kind: "parser-busy" }
  | { kind: "parent-gone" }
  | { kind: "expired" }
  | { kind: "error" };

/**
 * POST .../documents — multipart/form-data with the single part "file".
 * No Content-Type header is set: the platform fetch builds the multipart
 * boundary itself. Uploads legitimately take longer than the 10s read calls.
 * With synchronous indexing (dev default) the server reads scanned PDF pages
 * via OCR INSIDE this request (OCR-01, server limit
 * keplura.ocr.total-timeout-seconds = 900), so the client must wait at least
 * that long — a shorter timeout reported a stored document as failed.
 * Production indexes asynchronously and answers immediately.
 */
const UPLOAD_TIMEOUT_MS = 16 * 60 * 1000;
export async function uploadDocument(
  orgId: number,
  spaceId: number,
  kbId: number,
  file: File,
): Promise<UploadOutcome> {
  const form = new FormData();
  form.append("file", file, file.name);
  let response: Response;
  try {
    response = await desktopFetch(
      `${serverUrl}/api/v1/orgs/${orgId}/spaces/${spaceId}/knowledge-bases/${kbId}/documents`,
      { method: "POST", body: form, signal: AbortSignal.timeout(UPLOAD_TIMEOUT_MS) },
    );
  } catch {
    return { kind: "error" };
  }
  if (response.status === 201) {
    try {
      return { kind: "uploaded", document: (await response.json()) as KnowledgeDocument };
    } catch {
      return { kind: "error" };
    }
  }
  if (response.status === 400) {
    // Covers non-whitelisted extension, invalid UTF-8 text AND failed pdf/docx
    // extraction (MVP-5): the server contract is one generic 400 shape — the
    // UI copy names all causes honestly. Nothing was stored (rollback).
    return { kind: "rejected-type" };
  }
  if (response.status === 401) return { kind: "expired" };
  if (response.status === 404) return { kind: "parent-gone" };
  if (response.status === 413) return { kind: "too-large" };
  // MVP-5: parser pool saturated — the document itself is fine, capacity isn't.
  if (response.status === 503) return { kind: "parser-busy" };
  return { kind: "error" };
}

/** Outcome of POST .../search — coarse like every other call here. */
export type SearchOutcome =
  | { kind: "ok"; result: SearchResult }
  | { kind: "invalid-query" }
  | { kind: "parent-gone" }
  | { kind: "expired" }
  | { kind: "error" };

/**
 * POST .../search — RAG-light chunk search with citations over one kb
 * (M3d API). topK is left at the server default; the UI shows what comes.
 */
export async function searchKnowledgeBase(
  orgId: number,
  spaceId: number,
  kbId: number,
  query: string,
): Promise<SearchOutcome> {
  let response: Response;
  try {
    response = await desktopFetch(
      `${serverUrl}/api/v1/orgs/${orgId}/spaces/${spaceId}/knowledge-bases/${kbId}/search`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ query }),
        signal: AbortSignal.timeout(15000),
      },
    );
  } catch {
    return { kind: "error" };
  }
  if (response.status === 200) {
    try {
      return { kind: "ok", result: (await response.json()) as SearchResult };
    } catch {
      return { kind: "error" };
    }
  }
  if (response.status === 400) return { kind: "invalid-query" };
  if (response.status === 401) return { kind: "expired" };
  if (response.status === 404) return { kind: "parent-gone" };
  return { kind: "error" };
}

// ---------------------------------------------------------------------------
// M4b — chat flow: conversations, transcripts and the SSE streaming turn.
// Same coarse-outcome convention as every call above. The stream route is
// POST-only (server contract), so EventSource cannot drive it (GET-only API):
// the stream is consumed through desktopFetch + a manual SSE frame reader
// instead — which has the exact right side effect for Stop: aborting the
// fetch signal kills the read loop, and the server's next relay write breaks
// (that broken pipe IS the server-side stop signal, persisted as "aborted").
// ---------------------------------------------------------------------------

/** One conversation row from GET/POST .../chat/conversations. */
export interface ChatConversation {
  id: number;
  spaceId: number;
  knowledgeBaseId: number | null;
  title: string;
  createdAt: string;
  updatedAt: string;
}

/** Citation attached to an assistant answer (mirrors server Citation). */
export interface ChatCitation {
  documentId: number | null;
  filename: string;
  chunkIdx: number;
  score: number;
  pageNumber?: number | null;
  sectionTitle?: string | null;
  startOffset?: number | null;
  endOffset?: number | null;
  sourceUpdatedAt?: string | null;
}

/** One transcript row; status is the server's honest turn outcome. */
export interface ChatMessageDto {
  id: number;
  role: "user" | "assistant";
  content: string;
  model: string;
  citations: ChatCitation[];
  status: "complete" | "aborted" | "failed" | "incomplete";
  createdAt: string;
  /** PILOT 4e: owner's rating of an assistant answer. */
  feedback?: "up" | "down" | null;
}

export interface ChatTranscript {
  conversationId: number;
  messages: ChatMessageDto[];
  /** TU-02: tool call cards of this conversation (mine only), each bound to its question. */
  toolCalls?: ToolCallCard[];
}

/**
 * TU-02: Stop in the chat — pending confirmations of this and older turns end without a call,
 * and a turn still planning cannot create a confirmable card anymore. Best effort.
 */
export async function stopToolCalls(orgId: number, conversationId: number): Promise<boolean> {
  try {
    const response = await desktopFetch(
      `${serverUrl}/api/v1/orgs/${orgId}/chat/conversations/${conversationId}/tool-calls/stop`,
      { method: "POST", signal: AbortSignal.timeout(10000) },
    );
    return response.status === 204;
  } catch {
    return false;
  }
}

export type ToolCallDecisionOutcome =
  | { kind: "ok"; card: ToolCallCard }
  /** already decided, expired or cancelled — the card shows the server's current state */
  | { kind: "conflict"; card: ToolCallCard }
  | { kind: "gone" }
  | { kind: "expired" }
  | { kind: "error" };

/**
 * TU-02: POST .../tool-calls/{id}/decision — only the decision travels; the server executes
 * exactly the call it stored (arguments, target and rights are never taken from the client).
 * No automatic retry: an unclear outcome is resolved by re-reading the transcript.
 */
export async function decideToolCall(
  orgId: number,
  conversationId: number,
  callId: string,
  decision: "confirm" | "reject",
): Promise<ToolCallDecisionOutcome> {
  let response: Response;
  try {
    response = await desktopFetch(
      `${serverUrl}/api/v1/orgs/${orgId}/chat/conversations/${conversationId}/tool-calls/${encodeURIComponent(callId)}/decision`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ decision }),
        signal: AbortSignal.timeout(120000),
      },
    );
  } catch {
    return { kind: "error" };
  }
  if (response.status === 401) return { kind: "expired" };
  if (response.status === 404) return { kind: "gone" };
  if (response.status === 200 || response.status === 409) {
    try {
      const card = (await response.json()) as ToolCallCard;
      return response.status === 200 ? { kind: "ok", card } : { kind: "conflict", card };
    } catch {
      return { kind: "error" };
    }
  }
  return { kind: "error" };
}

/** Server-side prompt cap mirrored from keplura.chat.max-prompt-chars. */
export const CHAT_PROMPT_MAX_LENGTH = 4000;

export type ChatConversationsOutcome =
  | { kind: "ok"; conversations: ChatConversation[] }
  | { kind: "expired" }
  | { kind: "error" };

export type CreateConversationOutcome =
  | { kind: "created"; conversation: ChatConversation }
  | { kind: "parent-gone" }
  | { kind: "expired" }
  | { kind: "error" };

export type TranscriptOutcome =
  | { kind: "ok"; transcript: ChatTranscript }
  | { kind: "gone" }
  | { kind: "expired" }
  | { kind: "error" };

/** GET .../spaces/{spaceId}/chat/conversations — my threads in one space. */
export async function fetchConversations(
  orgId: number,
  spaceId: number,
): Promise<ChatConversationsOutcome> {
  try {
    const response = await desktopFetch(
      `${serverUrl}/api/v1/orgs/${orgId}/spaces/${spaceId}/chat/conversations`,
      { signal: AbortSignal.timeout(10000) },
    );
    if (response.status === 401) return { kind: "expired" };
    if (!response.ok) return { kind: "error" };
    return {
      kind: "ok",
      conversations: (await response.json()) as ChatConversation[],
    };
  } catch {
    return { kind: "error" };
  }
}

/** POST .../spaces/{spaceId}/chat/conversations — optional kb RAG context. */
export async function createConversation(
  orgId: number,
  spaceId: number,
  knowledgeBaseId: number | null,
): Promise<CreateConversationOutcome> {
  let response: Response;
  try {
    response = await desktopFetch(
      `${serverUrl}/api/v1/orgs/${orgId}/spaces/${spaceId}/chat/conversations`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(knowledgeBaseId === null ? {} : { knowledgeBaseId }),
        signal: AbortSignal.timeout(10000),
      },
    );
  } catch {
    return { kind: "error" };
  }
  if (response.status === 201) {
    try {
      return {
        kind: "created",
        conversation: (await response.json()) as ChatConversation,
      };
    } catch {
      return { kind: "error" };
    }
  }
  if (response.status === 401) return { kind: "expired" };
  if (response.status === 404) return { kind: "parent-gone" };
  return { kind: "error" };
}

/** GET .../chat/conversations/{id} — transcript with citations + statuses. */
export async function fetchTranscript(
  orgId: number,
  conversationId: number,
): Promise<TranscriptOutcome> {
  try {
    const response = await desktopFetch(
      `${serverUrl}/api/v1/orgs/${orgId}/chat/conversations/${conversationId}`,
      { signal: AbortSignal.timeout(10000) },
    );
    if (response.status === 401) return { kind: "expired" };
    if (response.status === 404) return { kind: "gone" };
    if (!response.ok) return { kind: "error" };
    return { kind: "ok", transcript: (await response.json()) as ChatTranscript };
  } catch {
    return { kind: "error" };
  }
}

/** DELETE .../chat/conversations/{id} — hard delete of the thread. */
export async function deleteConversation(
  orgId: number,
  conversationId: number,
): Promise<"deleted" | "gone" | "expired" | "error"> {
  try {
    const response = await desktopFetch(
      `${serverUrl}/api/v1/orgs/${orgId}/chat/conversations/${conversationId}`,
      { method: "DELETE", signal: AbortSignal.timeout(10000) },
    );
    if (response.status === 204) return "deleted";
    if (response.status === 401) return "expired";
    if (response.status === 404) return "gone";
    return "error";
  } catch {
    return "error";
  }
}

/** One parsed SSE frame (data payload kept raw; caller decides relevance). */
export interface SseFrame {
  event: string;
  data: string;
}

/**
 * Incremental SSE frame parser over the byte stream desktopFetch yields.
 * Line-splitting is decode-per-complete-line (TextDecoder stream mode) so a
 * multi-byte UTF-8 character split across network reads cannot corrupt text
 * — the same discipline the server relay applies on its side.
 */
export class SseFrameReader {
  private decoder = new TextDecoder("utf-8");
  private buffer = "";

  /** Feed one chunk; returns every COMPLETE frame (double-newline separated). */
  push(chunk: Uint8Array): SseFrame[] {
    this.buffer += this.decoder.decode(chunk, { stream: true });
    const frames: SseFrame[] = [];
    let idx: number;
    // SSE separates events with a blank line; tolerate \r\n framing
    while ((idx = this.buffer.indexOf("\n\n")) >= 0) {
      const raw = this.buffer.slice(0, idx);
      this.buffer = this.buffer.slice(idx + 2);
      const frame = parseFrameBlock(raw);
      if (frame) frames.push(frame);
    }
    return frames;
  }
}

function parseFrameBlock(block: string): SseFrame | null {
  let event = "message";
  const dataLines: string[] = [];
  for (const line of block.split("\n")) {
    if (line.startsWith("event:")) {
      event = line.slice(6).trim();
    } else if (line.startsWith("data:")) {
      dataLines.push(line.slice(5).trimStart());
    }
  }
  if (dataLines.length === 0) return null;
  return { event, data: dataLines.join("\n") };
}

/** Outcome vocabulary of one streaming turn (coarse; no server internals). */
export type ChatStreamEvent =
  | { kind: "delta"; text: string }
  | { kind: "done" }
  | { kind: "citations"; citations: ChatCitation[] }
  | { kind: "turn"; status: ChatMessageDto["status"] };

export type ChatStreamOutcome =
  /** Stream ended (cleanly or truncated) — the UI re-reads the transcript. */
  | { kind: "closed" }
  /** Stop button pressed — treated as closed, the server persists "aborted". */
  | { kind: "stopped" }
  | { kind: "unauthorized" }
  | { kind: "gone" }
  | { kind: "not-configured" }
  | { kind: "rate-limited"; retryAfterSeconds: number | null }
  | { kind: "error" };

/**
 * POST .../chat/conversations/{id}/stream — one streaming turn. Frames are
 * surfaced through `onEvent` as they arrive; `stopSignal` aborts the fetch
 * mid-stream (that IS the Stop button: the server records the partial answer
 * as "aborted" when its next write breaks). Every pre-first-byte error keeps
 * the gateway JSON shape (503 not-configured, 429 + Retry-After, 404 gone,
 * 401 expired) and maps to a coarse outcome.
 */
export async function streamChatTurn(
  orgId: number,
  conversationId: number,
  prompt: string,
  onEvent: (event: ChatStreamEvent) => void,
  stopSignal: AbortSignal,
  webSearch = false,
): Promise<ChatStreamOutcome> {
  let response: Response;
  try {
    response = await desktopFetch(
      `${serverUrl}/api/v1/orgs/${orgId}/chat/conversations/${conversationId}/stream`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(webSearch ? { prompt, webSearch: true } : { prompt }),
        signal: stopSignal,
      },
    );
  } catch (error) {
    if (stopSignal.aborted) return { kind: "stopped" };
    return { kind: "error" };
  }
  if (response.status === 401) return { kind: "unauthorized" };
  if (response.status === 404) return { kind: "gone" };
  if (response.status === 503) return { kind: "not-configured" };
  if (response.status === 429) {
    const header = response.headers.get("Retry-After");
    const seconds = header !== null ? Number.parseInt(header, 10) : NaN;
    return {
      kind: "rate-limited",
      retryAfterSeconds: Number.isFinite(seconds) && seconds > 0 ? seconds : null,
    };
  }
  if (!response.ok || !response.body) return { kind: "error" };

  const reader = response.body.getReader();
  const sse = new SseFrameReader();
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      for (const frame of sse.push(value)) {
        handleStreamFrame(frame, onEvent);
      }
    }
  } catch {
    // AbortError (Stop) or a dead connection: the honest signal is the
    // truncated stream itself — the transcript re-read shows the outcome.
    return stopSignal.aborted ? { kind: "stopped" } : { kind: "closed" };
  }
  return { kind: "closed" };
}

function handleStreamFrame(frame: SseFrame, onEvent: (e: ChatStreamEvent) => void) {
  if (frame.data === "[DONE]") {
    onEvent({ kind: "done" });
    return;
  }
  if (frame.event === "citations") {
    try {
      const parsed = JSON.parse(frame.data) as ChatCitation[];
      if (Array.isArray(parsed)) onEvent({ kind: "citations", citations: parsed });
    } catch {
      /* a corrupt trailer is decoration loss, not a turn failure */
    }
    return;
  }
  if (frame.event === "turn") {
    try {
      const parsed = JSON.parse(frame.data) as { status?: string };
      const status = parsed.status;
      if (
        status === "complete" ||
        status === "aborted" ||
        status === "failed" ||
        status === "incomplete"
      ) {
        onEvent({ kind: "turn", status });
      }
    } catch {
      /* same as above */
    }
    return;
  }
  // default OpenAI-style delta frame
  try {
    const parsed = JSON.parse(frame.data) as {
      choices?: { delta?: { content?: unknown } }[];
    };
    const content = parsed.choices?.[0]?.delta?.content;
    if (typeof content === "string" && content.length > 0) {
      onEvent({ kind: "delta", text: content });
    }
  } catch {
    /* malformed upstream frame: the relayed text may contain it; ignore */
  }
}

// ---------------------------------------------------------------------------
// SEC-2 — organization invites (docs/adr/0004 extended: an OWNER mints an
// expiring one-time token, an existing user redeems it as MEMBER). All routes
// ride desktopFetch, so the CSRF header is attached automatically like on
// every other state-changing call. No email is ever sent: the token string is
// shown ONCE in the create outcome and handed out inside the org out-of-band.
// ---------------------------------------------------------------------------

/** One pending invite as listed by GET /api/v1/orgs/{id}/invites (no token!). */
export interface OrgInvite {
  id: number;
  orgId: number;
  role: string;
  /** ISO-8601 instant after which the token is dead. */
  expiresAt: string;
  createdAt: string;
  /** Display name of the OWNER who minted it (server-resolved). */
  inviterName: string;
}

/** A member row of GET /api/v1/orgs/{id}/members (roster view). */
export interface OrgMember {
  userId: number;
  displayName: string;
  role: string;
  /** ISO-8601 instant of the grant. */
  joinedAt: string;
}

/** Outcome of POST /api/v1/orgs/{id}/invites. */
export type CreateInviteOutcome =
  /** token is delivered EXACTLY ONCE here — the server stores only its hash. */
  | { kind: "created"; invite: OrgInvite; token: string }
  | { kind: "expired" }
  | { kind: "forbidden" }
  | { kind: "error" };

/** Outcome of GET /api/v1/orgs/{id}/invites. */
export type ListInvitesOutcome =
  | { kind: "ok"; invites: OrgInvite[] }
  | { kind: "expired" }
  | { kind: "forbidden" }
  | { kind: "error" };

/** Outcome of DELETE /api/v1/orgs/{id}/invites/{inviteId}. */
export type RevokeInviteOutcome =
  | { kind: "revoked" }
  | { kind: "expired" }
  | { kind: "not-found" }
  | { kind: "error" };

/** Outcome of POST /api/v1/invites/accept. */
export type AcceptInviteOutcome =
  | { kind: "accepted"; orgId: number; orgName: string }
  /** token unknown / used / expired / revoked — one generic reason (404). */
  | { kind: "invalid-token" }
  | { kind: "already-member" }
  | { kind: "expired-session" }
  | { kind: "error" };

/** Outcome of GET /api/v1/orgs/{id}/members. */
export type ListMembersOutcome =
  | { kind: "ok"; members: OrgMember[] }
  | { kind: "expired" }
  | { kind: "forbidden" }
  | { kind: "error" };

/** POST /api/v1/orgs/{id}/invites — OWNER only; returns the plaintext token ONCE. */
export async function createInvite(orgId: number): Promise<CreateInviteOutcome> {
  let response: Response;
  try {
    response = await desktopFetch(`${serverUrl}/api/v1/orgs/${orgId}/invites`, {
      method: "POST",
      signal: AbortSignal.timeout(10000),
    });
  } catch {
    return { kind: "error" };
  }
  if (response.status === 401) return { kind: "expired" };
  if (response.status === 403) return { kind: "forbidden" };
  if (!response.ok) return { kind: "error" };
  try {
    const body = (await response.json()) as { invite: OrgInvite; token: string };
    return { kind: "created", invite: body.invite, token: body.token };
  } catch {
    return { kind: "error" };
  }
}

/** GET /api/v1/orgs/{id}/invites — pending invites, OWNER only. */
export async function listInvites(orgId: number): Promise<ListInvitesOutcome> {
  let response: Response;
  try {
    response = await desktopFetch(`${serverUrl}/api/v1/orgs/${orgId}/invites`, {
      signal: AbortSignal.timeout(10000),
    });
  } catch {
    return { kind: "error" };
  }
  if (response.status === 401) return { kind: "expired" };
  if (response.status === 403) return { kind: "forbidden" };
  if (!response.ok) return { kind: "error" };
  try {
    return { kind: "ok", invites: (await response.json()) as OrgInvite[] };
  } catch {
    return { kind: "error" };
  }
}

/** DELETE /api/v1/orgs/{id}/invites/{inviteId} — OWNER revokes a pending invite. */
export async function revokeInvite(orgId: number, inviteId: number): Promise<RevokeInviteOutcome> {
  let response: Response;
  try {
    response = await desktopFetch(`${serverUrl}/api/v1/orgs/${orgId}/invites/${inviteId}`, {
      method: "DELETE",
      signal: AbortSignal.timeout(10000),
    });
  } catch {
    return { kind: "error" };
  }
  if (response.status === 401) return { kind: "expired" };
  // 403 (not OWNER) and 404 (foreign/unknown invite) collapse to not-found:
  // the server never confirms an id it will not act on.
  if (response.status === 403 || response.status === 404) return { kind: "not-found" };
  if (!response.ok) return { kind: "error" };
  return { kind: "revoked" };
}

/** POST /api/v1/invites/accept {token} — an existing user joins as MEMBER. */
export async function acceptInvite(token: string): Promise<AcceptInviteOutcome> {
  let response: Response;
  try {
    response = await desktopFetch(`${serverUrl}/api/v1/invites/accept`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token }),
      signal: AbortSignal.timeout(10000),
    });
  } catch {
    return { kind: "error" };
  }
  if (response.status === 401) return { kind: "expired-session" };
  if (response.status === 409) return { kind: "already-member" };
  if (response.status === 404) return { kind: "invalid-token" };
  if (!response.ok) return { kind: "error" };
  try {
    const body = (await response.json()) as { orgId: number; orgName: string };
    return { kind: "accepted", orgId: body.orgId, orgName: body.orgName };
  } catch {
    return { kind: "error" };
  }
}

/** GET /api/v1/orgs/{id}/members — the roster, OWNER only. */
export async function listMembers(orgId: number): Promise<ListMembersOutcome> {
  let response: Response;
  try {
    response = await desktopFetch(`${serverUrl}/api/v1/orgs/${orgId}/members`, {
      signal: AbortSignal.timeout(10000),
    });
  } catch {
    return { kind: "error" };
  }
  if (response.status === 401) return { kind: "expired" };
  if (response.status === 403) return { kind: "forbidden" };
  if (!response.ok) return { kind: "error" };
  try {
    return { kind: "ok", members: (await response.json()) as OrgMember[] };
  } catch {
    return { kind: "error" };
  }
}
