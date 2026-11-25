// Chat-Produktivität: Vorlagen, globale Suche, Anhänge (PO-Freigabe 14.11.2026).
import { desktopFetch } from "./transport";
import { serverUrl } from "./api";
import { tr, serverText, translateServer } from "./i18n";

export type Res<T> = { kind: "ok"; value: T } | { kind: "error"; message: string } | { kind: "expired" };

export async function call<T>(method: string, path: string, body?: unknown, timeoutMs = 30000): Promise<Res<T>> {
  let r: Response;
  try {
    r = await desktopFetch(`${serverUrl}${path}`, {
      method,
      headers: body === undefined || body instanceof FormData ? undefined : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),
      signal: AbortSignal.timeout(timeoutMs),
    });
  } catch {
    return { kind: "error", message: tr("Der Server hat nicht geantwortet. Bitte versuche es erneut.") };
  }
  if (r.status === 401) return { kind: "expired" };
  if (!r.ok) {
    let message = r.status === 403 ? tr("Dafür fehlt dir die Berechtigung.")
      : r.status === 404 ? tr("Der Eintrag existiert nicht mehr.")
      : r.status === 413 ? tr("Die Datei ist zu groß.")
      : tr("Das hat nicht geklappt. Bitte versuche es erneut.");
    try {
      const e = (await r.json()) as { error?: string };
      if (e.error && r.status === 400) message = serverText(e.error);
    } catch {
      /* kein Inhalt */
    }
    return { kind: "error", message };
  }
  if (r.status === 204) return { kind: "ok", value: undefined as T };
  try {
    return { kind: "ok", value: translateServer((await r.json()) as T) };
  } catch {
    return { kind: "ok", value: undefined as T };
  }
}

export interface PromptTemplate { id: number; spaceId: number | null; title: string; body: string; canEdit: boolean }

export const listTemplates = (orgId: number, spaceId: number | null) =>
  call<PromptTemplate[]>("GET", `/api/v1/orgs/${orgId}/templates${spaceId ? `?spaceId=${spaceId}` : ""}`);
export const createTemplate = (orgId: number, spaceId: number | null, title: string, body: string) =>
  call<PromptTemplate>("POST", `/api/v1/orgs/${orgId}/templates`, { spaceId, title, body });
export const deleteTemplate = (orgId: number, id: number) => call<void>("DELETE", `/api/v1/orgs/${orgId}/templates/${id}`);

export interface GlobalSearchResult {
  spaces: { id: number; name: string }[];
  knowledgeBases: { id: number; spaceId: number; name: string; spaceName: string }[];
  documents: { id: number; kbId: number; spaceId: number; filename: string; kbName: string }[];
  conversations: { id: number; spaceId: number; title: string; knowledgeBaseId: number | null }[];
}
export const globalSearch = (orgId: number, q: string) =>
  call<GlobalSearchResult>("GET", `/api/v1/orgs/${orgId}/search/global?q=${encodeURIComponent(q)}`, undefined, 10000);

export interface ChatAttachment { id: number; filename: string; charCount: number; truncated: boolean; createdAt: string }
export interface ChatContext { knowledgeBaseId: number | null; extraKnowledgeBaseIds: number[]; attachments: ChatAttachment[] }
const convPath = (orgId: number, cid: number) => `/api/v1/orgs/${orgId}/chat/conversations/${cid}`;
export const getChatContext = (orgId: number, cid: number) => call<ChatContext>("GET", `${convPath(orgId, cid)}/context`);
export interface ChatToolSelection {
  selectedIds: string[] | null;
  tools: { id: string; name: string; source: string; risk: "READ" | "WRITE" | "CRITICAL" }[];
  /** TU-05b: tools the current chat model offers by default (null = every approved tool). */
  modelDefaultIds: string[] | null;
  /** TU-05b: the current chat model never gets tools. */
  modelToolsOff: boolean;
}
export const getChatToolSelection = (orgId: number, cid: number) =>
  call<ChatToolSelection>("GET", `${convPath(orgId, cid)}/tool-selection`);
export const setChatToolSelection = (orgId: number, cid: number, selectedIds: string[] | null) =>
  call<ChatToolSelection>("PUT", `${convPath(orgId, cid)}/tool-selection`, { selectedIds });
export const setChatKnowledgeBases = (orgId: number, cid: number, knowledgeBaseIds: number[]) =>
  call<ChatContext>("PUT", `${convPath(orgId, cid)}/knowledge-bases`, { knowledgeBaseIds });
export function uploadChatAttachment(orgId: number, cid: number, file: File) {
  const fd = new FormData();
  fd.append("file", file);
  return call<ChatAttachment>("POST", `${convPath(orgId, cid)}/attachments`, fd, 120000);
}
export const deleteChatAttachment = (orgId: number, cid: number, id: number) =>
  call<void>("DELETE", `${convPath(orgId, cid)}/attachments/${id}`);

export const webSearchStatus = (orgId: number) =>
  call<{ enabled: boolean; provider: string }>("GET", `/api/v1/orgs/${orgId}/web-search/status`);

export interface WebSearchAdmin {
  source: "admin" | "env"; provider: "none" | "searxng" | "brave" | "tavily"; url: string; hasApiKey: boolean;
  results: number; blockedDomains: string; enabled: boolean; secretStorageAvailable: boolean;
}
export const getWebSearchAdmin = () => call<WebSearchAdmin>("GET", "/api/v1/admin/web-search");
export const putWebSearchAdmin = (input: { provider: string; url: string; apiKey: string | null; results: number; blockedDomains: string }) =>
  call<WebSearchAdmin>("PUT", "/api/v1/admin/web-search", input);
export const testWebSearchAdmin = () =>
  call<{ ok: boolean; message: string; results: { title: string; url: string; snippet: string }[] }>("POST", "/api/v1/admin/web-search/test", undefined, 30000);

/** TU-01: risk class is set by an admin only; unclassified tools stay blocked. */
export type ToolRisk = "READ" | "WRITE" | "CRITICAL";
export interface McpTool {
  name: string; description: string;
  /** effectively approved (classified, schema supported, ticked); CRITICAL still needs a per-call confirmation */
  enabled: boolean;
  risk: ToolRisk | null; schemaSupported: boolean; schemaProblem: string | null;
  /** approval from before TU-01 without a risk class — no longer effective */
  legacyApproval: boolean;
}
export interface McpServer {
  id: number; name: string; url: string; hasToken: boolean; enabled: boolean;
  tools: McpTool[]; lastCheckedAt: string | null; lastError: string | null;
  allSpaces: boolean; spaceIds: number[];
}
const MCP = "/api/v1/admin/mcp-servers";
export const listMcpServers = () => call<McpServer[]>("GET", MCP);
export interface OrgToolSettings { knowledgeEnabled: boolean; webSearchEnabled: boolean; webSearchAvailable: boolean; servers: {serverId: number; name: string; internal: boolean}[] }
export const getOrgToolSettings = (orgId: number) => call<OrgToolSettings>("GET", `/api/v1/orgs/${orgId}/tools/settings`);
export const updateOrgToolSettings = (orgId: number,input: {knowledgeEnabled?: boolean; webSearchEnabled?: boolean; serverId?: number; internal?: boolean}) =>
  call<OrgToolSettings>("PUT", `/api/v1/orgs/${orgId}/tools/settings`, input);
export const createMcpServer = (input: { name: string; url: string; token: string | null }) => call<McpServer>("POST", MCP, input, 60000);
export const updateMcpServer = (id: number, input: { name?: string; url?: string; token?: string | null; enabled?: boolean; enabledTools?: string[]; allSpaces?: boolean; spaceIds?: number[]; toolRisks?: Record<string, ToolRisk | ""> }) =>
  call<McpServer>("PATCH", `${MCP}/${id}`, input);
export const refreshMcpServer = (id: number) => call<McpServer>("POST", `${MCP}/${id}/refresh`, undefined, 60000);
export const deleteMcpServer = (id: number) => call<void>("DELETE", `${MCP}/${id}`);
export const listMcpSpaces = () => call<{ id: number; name: string; orgName: string }[]>("GET", `${MCP}/spaces`);
export const listMcpCalls = () => call<{ called_at: string; server_name: string; tool_name: string; duration_ms: number; ok: boolean; error: string | null; user_id: number; risk: ToolRisk | null }[]>("GET", `${MCP}/calls`);
