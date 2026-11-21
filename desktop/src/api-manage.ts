// REDESIGN: rename/delete/re-index calls (server: DocumentController,
// KnowledgeBaseController, SpaceController, OrgController). Same transport
// and error discipline as api.ts — coarse outcomes, no server text in the UI.
import { desktopFetch } from "./transport";
import { serverUrl } from "./api";
import { tr } from "./i18n";

export type ManageOutcome<T = void> =
  | { kind: "ok"; data: T }
  | { kind: "forbidden" }
  | { kind: "conflict" }
  | { kind: "invalid" }
  | { kind: "not-found" }
  | { kind: "expired" }
  | { kind: "error" };

async function send<T>(method: "PATCH" | "DELETE" | "POST" | "PUT", path: string, body?: unknown): Promise<ManageOutcome<T>> {
  let response: Response;
  try {
    response = await desktopFetch(`${serverUrl}${path}`, {
      method,
      headers: body === undefined ? undefined : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(60000),
    });
  } catch {
    return { kind: "error" };
  }
  if (response.ok) {
    if (response.status === 204) return { kind: "ok", data: undefined as T };
    try {
      return { kind: "ok", data: (await response.json()) as T };
    } catch {
      return { kind: "ok", data: undefined as T };
    }
  }
  switch (response.status) {
    case 400: return { kind: "invalid" };
    case 401: return { kind: "expired" };
    case 403: return { kind: "forbidden" };
    case 404: return { kind: "not-found" };
    case 409: return { kind: "conflict" };
    default: return { kind: "error" };
  }
}

const kbPath = (o: number, s: number, k: number) => `/api/v1/orgs/${o}/spaces/${s}/knowledge-bases/${k}`;

export const deleteDocument = (o: number, s: number, k: number, d: number) =>
  send("DELETE", `${kbPath(o, s, k)}/documents/${d}`);
export const reindexDocument = <T>(o: number, s: number, k: number, d: number) =>
  send<T>("POST", `${kbPath(o, s, k)}/documents/${d}/reindex`);
export const renameKnowledgeBase = <T>(o: number, s: number, k: number, name: string) =>
  send<T>("PATCH", kbPath(o, s, k), { name });
export const deleteKnowledgeBase = (o: number, s: number, k: number) => send("DELETE", kbPath(o, s, k));
export const renameSpace = <T>(o: number, s: number, name: string) =>
  send<T>("PATCH", `/api/v1/orgs/${o}/spaces/${s}`, { name });
export const deleteSpace = (o: number, s: number) => send("DELETE", `/api/v1/orgs/${o}/spaces/${s}`);
export const renameOrg = <T>(o: number, name: string) => send<T>("PATCH", `/api/v1/orgs/${o}`, { name });
export const deleteOrg = (o: number) => send("DELETE", `/api/v1/orgs/${o}`);
export const removeMember = (o: number, userId: number) => send("DELETE", `/api/v1/orgs/${o}/members/${userId}`);

/** Plain-language message for a failed management call. */
export function manageErrorText(kind: Exclude<ManageOutcome["kind"], "ok">): string {
  switch (kind) {
    case "forbidden": return tr("Dafür fehlt dir die Berechtigung. Wende dich an eine Verwalterin oder einen Verwalter.");
    case "conflict": return tr("Dieser Name ist bereits vergeben.");
    case "invalid": return tr("Die Eingabe ist ungültig.");
    case "not-found": return tr("Der Eintrag existiert nicht mehr. Die Ansicht wurde aktualisiert.");
    case "expired": return tr("Deine Sitzung ist abgelaufen. Bitte melde dich erneut an.");
    default: return tr("Das hat nicht geklappt. Bitte versuche es später erneut.");
  }
}

/** PILOT 4e: rate one assistant answer of the caller's own conversation (null clears). */
export const rateAnswer = (o: number, conversationId: number, messageId: number, rating: "up" | "down" | null) =>
  send<{ messageId: number; rating: "up" | "down" | null }>(
    "PUT",
    `/api/v1/orgs/${o}/chat/conversations/${conversationId}/messages/${messageId}/feedback`,
    { rating },
  );

/** PILOT 4e: change the caller's own password (start password → own password). */
export const changePassword = (currentPassword: string, newPassword: string) =>
  send("POST", "/api/v1/auth/password", { currentPassword, newPassword });

export interface AddMemberResult {
  userId: number;
  displayName: string;
  createdAccount: boolean;
  /** only for a newly created account — shown once, never stored client-side */
  startPassword: string | null;
}

/** PILOT 4e: OWNER adds a colleague by e-mail (creates the account if needed). */
export const addMember = (o: number, email: string, displayName: string) =>
  send<AddMemberResult>("POST", `/api/v1/orgs/${o}/members`, { email, displayName });
