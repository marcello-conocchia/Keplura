// ADR-0012 (Schritt 5): Verzeichnis-Anbindung und Datenquellen in der Administration.
// Gleicher Transport/CSRF wie api.ts; verständliche Ergebnisse statt roher Statuscodes.
import { desktopFetch } from "./transport";
import { serverUrl } from "./api";
import { tr } from "./i18n";

export type Result<T> =
  | { kind: "ok"; value: T }
  | { kind: "invalid"; message: string }
  | { kind: "busy"; message: string }
  | { kind: "forbidden" }
  | { kind: "expired" }
  | { kind: "error" };

async function call<T>(method: string, path: string, body?: unknown, timeoutMs = 30000): Promise<Result<T>> {
  let r: Response;
  try {
    r = await desktopFetch(`${serverUrl}${path}`, {
      method,
      headers: body === undefined ? undefined : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(timeoutMs),
    });
  } catch {
    return { kind: "error" };
  }
  if (r.status === 401) return { kind: "expired" };
  if (r.status === 403) return { kind: "forbidden" };
  if (r.status === 400 || r.status === 404 || r.status === 409) {
    let message = r.status === 404 ? tr("Der Eintrag existiert nicht mehr.") : tr("Ungültige Eingabe.");
    try {
      const e = (await r.json()) as { error?: string };
      if (e.error && r.status !== 404) message = e.error;
    } catch {
      /* kein Inhalt */
    }
    return r.status === 409 ? { kind: "busy", message } : { kind: "invalid", message };
  }
  if (!r.ok) return { kind: "error" };
  if (r.status === 202 || r.status === 204) return { kind: "ok", value: undefined as T };
  try {
    return { kind: "ok", value: (await r.json()) as T };
  } catch {
    return { kind: "ok", value: undefined as T };
  }
}

export function resultText(r: Exclude<Result<unknown>, { kind: "ok" }>): string {
  switch (r.kind) {
    case "invalid":
    case "busy":
      return r.message;
    case "forbidden":
      return tr("Dafür sind Administrationsrechte nötig.");
    case "expired":
      return tr("Deine Sitzung ist abgelaufen. Bitte melde dich erneut an.");
    default:
      return tr("Der Server hat nicht geantwortet. Bitte versuche es erneut.");
  }
}

// ------------------------------------------------------------- Verzeichnis

export interface DirectoryConnection {
  id: number;
  orgId: number;
  kind: "AD" | "LDAP";
  displayName: string;
  url: string;
  baseDn: string;
  bindDn: string;
  secretSet: boolean;
  userFilter: string;
  startTls: boolean;
  allowPlainLdap: boolean;
  loginEnabled: boolean;
  autoProvision: boolean;
  enabled: boolean;
}

export type DirectoryInput = Partial<Omit<DirectoryConnection, "id" | "secretSet">> & { bindPassword?: string };

export interface DirectoryTest { ok: boolean; message: string; usersFound: number }
export interface DirectoryLookup { found: boolean; message: string; displayName: string | null; email: string | null; principals: string[] }

const D = "/api/v1/admin/directories";
export const listDirectories = () => call<DirectoryConnection[]>("GET", D);
export const createDirectory = (input: DirectoryInput) => call<DirectoryConnection>("POST", D, input);
export const updateDirectory = (id: number, input: DirectoryInput) => call<DirectoryConnection>("PATCH", `${D}/${id}`, input);
export const deleteDirectory = (id: number) => call<void>("DELETE", `${D}/${id}`);
export const testDirectory = (id: number) => call<DirectoryTest>("POST", `${D}/${id}/test`, {}, 60000);
export const lookupDirectoryUser = (id: number, login: string) =>
  call<DirectoryLookup>("POST", `${D}/${id}/lookup`, { login }, 60000);

// ------------------------------------------------------------- Datenquellen

export interface DataSource {
  id: number;
  orgId: number;
  spaceId: number;
  knowledgeBaseId: number;
  kind: "SMB" | "LOCAL" | "SHAREPOINT";
  displayName: string;
  location: string;
  username: string | null;
  tenantId: string | null;
  clientId: string | null;
  secretSet: boolean;
  includePatterns: string;
  excludePatterns: string;
  maxFileBytes: number;
  mirrorPermissions: boolean;
  intervalMinutes: number;
  enabled: boolean;
  lastRunStartedAt: string | null;
  lastRunFinishedAt: string | null;
  lastRunStatus: "RUNNING" | "OK" | "PARTIAL" | "FAILED" | null;
  lastRunMessage: string | null;
  filesSeen: number;
  filesIndexed: number;
  filesFailed: number;
}

export type DataSourceInput = Partial<Omit<DataSource, "id" | "secretSet" | "lastRunStartedAt" | "lastRunFinishedAt"
  | "lastRunStatus" | "lastRunMessage" | "filesSeen" | "filesIndexed" | "filesFailed">> & { password?: string };

export interface DataSourcePreview {
  filesFound: number;
  filesAccepted: number;
  bytesAccepted: number;
  skippedType: number;
  skippedSize: number;
  skippedPattern: number;
  sample: string[];
  principals: { principal: string; files: number; label: string | null }[];
  aclSampled: number;
  aclUnreadable: number;
  /** Häufigster Grund für unlesbare Rechte (z. B. NFS oder erweiterte Linux-Rechte), sonst null. */
  aclUnreadableReason: string | null;
  message: string;
}

export interface DataSourceCapabilities { localRoots: string[]; kinds: string[] }

const S = "/api/v1/admin/datasources";
export const dataSourceCapabilities = () => call<DataSourceCapabilities>("GET", `${S}/capabilities`);
export const listDataSources = () => call<DataSource[]>("GET", S);
export const createDataSource = (input: DataSourceInput) => call<DataSource>("POST", S, input);
export const updateDataSource = (id: number, input: DataSourceInput) => call<DataSource>("PATCH", `${S}/${id}`, input);
export const deleteDataSource = (id: number) => call<void>("DELETE", `${S}/${id}`, undefined, 120000);
export const previewDataSource = (id: number) => call<DataSourcePreview>("POST", `${S}/${id}/preview`, {}, 300000);
export const runDataSource = (id: number) => call<void>("POST", `${S}/${id}/run`, {});
export const dataSourceProblems = (id: number) =>
  call<{ relativePath: string; error: string }[]>("GET", `${S}/${id}/problems`);

// ------------------------------------------------------------- Oversharing-Check (RISK-01)

export interface ExposureReport {
  state: "RUNNING" | "DONE" | "FAILED";
  startedAt: string;
  finishedAt: string | null;
  filesTotal: number;
  filesChecked: number;
  broadFiles: number;
  sensitiveBroadFiles: number;
  sensitiveFiles: number;
  unreadable: number;
  mirrorPermissions: boolean;
  truncated: boolean;
  broadGroups: { principal: string; label: string | null; files: number }[];
  folders: { folder: string; broadFiles: number; sensitiveFiles: number }[];
  findings: { relativePath: string; categories: string[]; broadGroups: string[] }[];
  message: string | null;
}

export const startExposureScan = (id: number) => call<void>("POST", `${S}/${id}/exposure`, {});
/** 404 (kind "invalid") heißt hier: noch keine Prüfung seit dem letzten Serverstart. */
export const exposureReport = (id: number) => call<ExposureReport>("GET", `${S}/${id}/exposure`);
export const excludeFromSource = (id: number, paths: string[]) =>
  call<DataSource>("POST", `${S}/${id}/exclude`, { paths });

/** Lesbare Bezeichnung eines Prinzipals für die Rechte-Vorschau. */
const wellKnownSids = (): Record<string, string> => ({
  "S-1-5-18": tr("SYSTEM (Windows-Dienst)"),
  "S-1-5-32-544": tr("Administratoren (lokal)"),
  "S-1-5-32-545": tr("Benutzer (lokal)"),
  "S-1-5-32-549": tr("Server-Operatoren"),
  "S-1-3-0": tr("Ersteller-Besitzer"),
});

export function principalLabel(p: string, resolved?: string | null): string {
  if (resolved) return resolved;
  if (p === "everyone") return tr("Alle angemeldeten Personen");
  if (p.startsWith("sid:") && wellKnownSids()[p.slice(4)]) return wellKnownSids()[p.slice(4)];
  if (p.startsWith("sid:")) return tr("Windows-Konto/-Gruppe {0}", p.slice(4));
  if (p.startsWith("posix-uid:")) return tr("Linux-Benutzer (UID {0})", p.slice(10));
  if (p.startsWith("posix-gid:")) return tr("Linux-Gruppe (GID {0})", p.slice(10));
  if (p.startsWith("ldap-dn:")) return p.slice(8);
  if (p.startsWith("entra-tenant:")) return tr("Alle Personen der Microsoft-365-Organisation");
  if (p.startsWith("entra:")) return tr("Microsoft-365-Benutzer/-Gruppe ({0}…)", p.slice(6, 14));
  return p;
}

// ------------------------------------------------------------- Antwortqualität (DS-5)

export interface QualityQuestion {
  id: number;
  question: string;
  expectedDocument: string;
  expectedPhrase: string | null;
  lastHit: boolean | null;
  lastRank: number | null;
  lastTopDocument: string | null;
}
export interface QualityRun { id: number; startedAt: string; finishedAt: string | null; total: number; hits: number; percent: number }
export interface QualityOverview { questions: QualityQuestion[]; runs: QualityRun[] }
export interface QualityTarget { orgId: number; spaceId: number; knowledgeBaseId: number }

const Q = "/api/v1/admin/quality";
export const qualityOverview = (kbId: number) => call<QualityOverview>("GET", `${Q}?knowledgeBaseId=${kbId}`);
export const addQualityQuestion = (t: QualityTarget, question: string, expectedDocument: string, expectedPhrase: string) =>
  call<QualityQuestion>("POST", `${Q}/questions`, { ...t, question, expectedDocument, expectedPhrase });
export const importQualityQuestions = (t: QualityTarget, csv: string) =>
  call<{ imported: number; skipped: number; problems: string[] }>("POST", `${Q}/questions/import`, { ...t, csv });
export const deleteQualityQuestion = (id: number) => call<void>("DELETE", `${Q}/questions/${id}`);
export const runQualityCheck = (t: QualityTarget) => call<QualityRun>("POST", `${Q}/runs`, t, 600000);
export const feedbackStats = (orgId: number) =>
  call<{ up: number; down: number; answersLast30Days: number }>("GET", `${Q}/feedback?orgId=${orgId}`);

// ------------------------------------------------------------- Rechte-Nachweis (ACCESS-01)

export interface AccessUser { id: number; email: string; displayName: string; groups: number; groupsRefreshedAt: string | null }
export interface AccessDocument { id: number; filename: string; location: string; origin: string }
export interface AccessStep { ok: boolean | null; title: string; detail: string | null }
export interface AccessRow { principal: string; label: string; allow: boolean; matchesUser: boolean }
export interface AccessExplanation {
  userId: number; userName: string; documentId: number; filename: string; location: string;
  visible: boolean; consistent: boolean; steps: AccessStep[]; acl: AccessRow[]; userGroups: AccessRow[];
  groupsRefreshedAt: string | null;
}
export interface AccessOverview {
  userId: number; userName: string; groupsRefreshedAt: string | null;
  knowledgeBases: { kbId: number; location: string; total: number; visible: number }[];
}
const A = "/api/v1/admin/access";
export const searchAccessUsers = (q: string) => call<AccessUser[]>("GET", `${A}/users?q=${encodeURIComponent(q)}`);
export const searchAccessDocuments = (q: string) => call<AccessDocument[]>("GET", `${A}/documents?q=${encodeURIComponent(q)}`);
export const explainAccess = (userId: number, documentId: number) =>
  call<AccessExplanation>("GET", `${A}/explain?userId=${userId}&documentId=${documentId}`, undefined, 60000);
export const accessOverview = (userId: number) => call<AccessOverview>("GET", `${A}/overview?userId=${userId}`, undefined, 60000);
