// ADMIN-KI desktop API (docs/adr/0008): provider + model upkeep, connection
// test, system status, RAG settings and the one-time .env seed offer.
//
// Same conventions as api.ts: every call rides desktopFetch (native
// transport, ADR-0005), outcomes are coarse ({kind:"expired"} drops the
// session store-side, {kind:"forbidden"} = logged in WITHOUT the ADMIN role —
// the server is the enforcement, this UI is only the decoration), and no
// server error body detail leaks into the UI.
//
// NOTE on shapes: they mirror the server records in
// com.keplura.server.admin.*Service — the secret is write-only: a ProviderDto
// only ever carries {secretSet, secretMasked}, never a ciphertext.

import { desktopFetch, serverOrigin } from "./transport";
import { tr, serverText, translateServer } from "./i18n";

const base = `${serverOrigin}/api/v1/admin`;

export interface AdminProvider {
  id: number;
  name: string;
  providerType: "ollama" | "vllm" | "api";
  baseUrl: string;
  secretSet: boolean;
  secretMasked: string;
  timeoutSeconds: number;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface AdminModel {
  id: number;
  providerId: number;
  providerName: string;
  name: string;
  technicalModelId: string;
  modelType: "CHAT" | "EMBEDDING";
  embeddingDim: number | null;
  maxOutputTokens: number | null;
  temperature: number | null;
  enabled: boolean;
  isDefault: boolean;
  createdAt: string;
  updatedAt: string;
  /** TU-05b: chat models only — AUTO offers tools, OFF never does. */
  toolUse: "AUTO" | "OFF";
  /** null = every approved tool kind by default, otherwise these kinds. */
  toolDefaults: string[] | null;
}

export const MODEL_TOOL_KINDS = ["knowledge", "attachments", "web", "mcp", "openapi", "ask"] as const;

export interface ConnectionTestOutcomeView {
  reachable: boolean;
  code: string;
}

export interface ComponentState {
  state: string;
  detail: string;
}

export interface SeedOffer {
  available: boolean;
  hasKey: boolean;
  providerType: string;
  baseUrl: string;
  chatModel: string;
}

export interface SeedResult {
  created: boolean;
  message: string;
}

/** Coarse outcome shared by the admin write calls. */
export type AdminMutationOutcome<T> =
  | { kind: "ok"; value: T }
  | { kind: "invalid"; message: string }
  | { kind: "forbidden" }
  | { kind: "expired" }
  | { kind: "unavailable" } // 503: master key missing (secret write)
  | { kind: "error" };

async function getJson<T>(path: string): Promise<{ kind: "ok"; value: T } | { kind: "expired" } | { kind: "forbidden" } | { kind: "error" }> {
  try {
    const response = await desktopFetch(`${base}${path}`, { signal: AbortSignal.timeout(15000) });
    if (response.status === 401) return { kind: "expired" };
    if (response.status === 403) return { kind: "forbidden" };
    if (!response.ok) return { kind: "error" };
    return { kind: "ok", value: translateServer((await response.json()) as T) };
  } catch {
    return { kind: "error" };
  }
}

async function sendJson<T>(method: string, path: string, body: unknown): Promise<AdminMutationOutcome<T>> {
  let response: Response;
  try {
    response = await desktopFetch(`${base}${path}`, {
      method,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body ?? {}),
      signal: AbortSignal.timeout(20000),
    });
  } catch {
    return { kind: "error" };
  }
  if (response.status === 401) return { kind: "expired" };
  if (response.status === 403) return { kind: "forbidden" };
  if (response.status === 503) return { kind: "unavailable" };
  if (response.status === 400 || response.status === 404) {
    // 400 body is the stable {error, code} pair; the message is server-owned
    // generic text (never upstream detail) so it is safe to show.
    try {
      const err = (await response.json()) as { error?: string };
      return { kind: "invalid", message: err.error ? serverText(err.error) : tr("Ungültige Eingabe") };
    } catch {
      return { kind: "invalid", message: tr("Ungültige Eingabe") };
    }
  }
  if (!response.ok) return { kind: "error" };
  try {
    return { kind: "ok", value: translateServer((await response.json()) as T) };
  } catch {
    return { kind: "error" };
  }
}

// ---- providers --------------------------------------------------------------

export const fetchProviders = () => getJson<AdminProvider[]>("/providers");

export const createProvider = (input: {
  name: string;
  providerType: string;
  baseUrl: string;
  secret?: string | null;
  timeoutSeconds?: number | null;
  enabled?: boolean | null;
}) => sendJson<AdminProvider>("POST", "/providers", input);

/** null/omitted field = unchanged; blank secret KEEPS the stored one,
 *  non-empty replaces it (PO discipline — clearing has no offered verb). */
export const patchProvider = (id: number, input: {
  name?: string | null;
  baseUrl?: string | null;
  timeoutSeconds?: number | null;
  enabled?: boolean | null;
  secret?: string | null;
}) => sendJson<AdminProvider>("PATCH", `/providers/${id}`, input);

export const testProvider = (id: number) =>
  sendJson<ConnectionTestOutcomeView>("POST", `/providers/${id}/test`, {});

// ---- models -----------------------------------------------------------------

export const fetchModels = () => getJson<AdminModel[]>("/models");

export const createModel = (input: {
  providerId: number;
  name: string;
  technicalModelId: string;
  modelType: string;
  embeddingDim?: number | null;
  maxOutputTokens?: number | null;
  temperature?: number | null;
  enabled?: boolean | null;
}) => sendJson<AdminModel>("POST", "/models", input);

export const patchModel = (id: number, input: {
  name?: string | null;
  technicalModelId?: string | null;
  maxOutputTokens?: number | null;
  temperature?: number | null;
  enabled?: boolean | null;
}) => sendJson<AdminModel>("PATCH", `/models/${id}`, input);

/** TU-05b: full replace of a chat model's tool policy. */
export const updateModelTools = (id: number, input: { toolUse: "AUTO" | "OFF"; toolDefaults: string[] | null }) =>
  sendJson<AdminModel>("PUT", `/models/${id}/tools`, input);

export const setModelDefault = (id: number) =>
  sendJson<AdminModel>("PUT", `/models/${id}/default`, null);

// ---- system status / RAG / seed ----------------------------------------------

export const fetchSystemStatus = () =>
  getJson<Record<string, ComponentState>>("/system-status");

export const fetchRagSettings = () => getJson<Record<string, unknown>>("/rag-settings");

export const fetchSeedOffer = () => getJson<SeedOffer>("/seed-offer");

export const seedFromEnv = () => sendJson<SeedResult>("POST", "/seed-from-env", {});

/** PILOT 4e: Stand der letzten geprüften Sicherung (geschrieben von scripts/backup.sh). */
export interface BackupStatus {
  statusFileFound: boolean;
  lastBackup: {
    finishedAt: string;
    backupName: string;
    databaseBytes: number;
    documentsBytes: number;
    verified: boolean;
    online: boolean;
    ageHours: number;
    stale: boolean;
  } | null;
}

export function fetchBackupStatus() {
  return getJson<BackupStatus>("/api/v1/admin/backup");
}
