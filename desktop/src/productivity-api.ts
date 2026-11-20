// Chat-Produktivität: Vorlagen, globale Suche, Anhänge (PO-Freigabe 14.11.2026).
import { desktopFetch } from "./transport";
import { serverUrl } from "./api";

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
    return { kind: "error", message: "Der Server hat nicht geantwortet. Bitte versuche es erneut." };
  }
  if (r.status === 401) return { kind: "expired" };
  if (!r.ok) {
    let message = r.status === 403 ? "Dafür fehlt dir die Berechtigung."
      : r.status === 404 ? "Der Eintrag existiert nicht mehr."
      : r.status === 413 ? "Die Datei ist zu groß."
      : "Das hat nicht geklappt. Bitte versuche es erneut.";
    try {
      const e = (await r.json()) as { error?: string };
      if (e.error && r.status === 400) message = e.error;
    } catch {
      /* kein Inhalt */
    }
    return { kind: "error", message };
  }
  if (r.status === 204) return { kind: "ok", value: undefined as T };
  try {
    return { kind: "ok", value: (await r.json()) as T };
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

export interface McpServer {
  id: number; name: string; url: string; hasToken: boolean; enabled: boolean;
  tools: { name: string; description: string; enabled: boolean }[]; lastCheckedAt: string | null; lastError: string | null;
  allSpaces: boolean; spaceIds: number[];
}
const MCP = "/api/v1/admin/mcp-servers";
export const listMcpServers = () => call<McpServer[]>("GET", MCP);
export const createMcpServer = (input: { name: string; url: string; token: string | null }) => call<McpServer>("POST", MCP, input, 60000);
export const updateMcpServer = (id: number, input: { name?: string; url?: string; token?: string | null; enabled?: boolean; enabledTools?: string[]; allSpaces?: boolean; spaceIds?: number[] }) =>
  call<McpServer>("PATCH", `${MCP}/${id}`, input);
export const refreshMcpServer = (id: number) => call<McpServer>("POST", `${MCP}/${id}/refresh`, undefined, 60000);
export const deleteMcpServer = (id: number) => call<void>("DELETE", `${MCP}/${id}`);
export const listMcpSpaces = () => call<{ id: number; name: string; orgName: string }[]>("GET", `${MCP}/spaces`);
export const listMcpCalls = () => call<{ called_at: string; server_name: string; tool_name: string; duration_ms: number; ok: boolean; error: string | null; user_id: number }[]>("GET", `${MCP}/calls`);
