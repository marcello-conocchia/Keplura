// TU-02 — tool call cards in the chat: pure helpers (no Vue, no I/O) so they can be tested with
// node:test like the other *.test.cjs files. The server is the authority for every state; the UI
// only renders what the server says and never invents a successful outcome.

export type ToolCallStatus =
  | "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED" | "REJECTED" | "BLOCKED" | "EXPIRED" | "CANCELLED" | "UNKNOWN";

/** One card as the server sends it (ToolExecutionService.ToolCallView). */
export interface ToolCallCard {
  id: string;
  promptMessageId: number | null;
  tool: string;
  source: string;
  risk: "READ" | "WRITE" | "CRITICAL" | null;
  arguments: Record<string, unknown> | null;
  status: ToolCallStatus;
  needsConfirmation: boolean;
  /** Tool output — foreign content, rendered as plain text only. */
  result: string | null;
  /** Generic server message (German key, translated via serverText). */
  error: string | null;
  createdAt: string;
  expiresAt: string | null;
}

export type Tone = "ok" | "wait" | "neutral" | "error" | "info";

/** German label (tr key) + pill tone per status. Unknown statuses are shown honestly as unclear. */
export function statusMeta(status: string): { label: string; tone: Tone } {
  switch (status) {
    case "PENDING": return { label: "Wartet auf Bestätigung", tone: "wait" };
    case "RUNNING": return { label: "Läuft", tone: "info" };
    case "SUCCEEDED": return { label: "Erfolgreich", tone: "ok" };
    case "FAILED": return { label: "Fehlgeschlagen", tone: "error" };
    case "REJECTED": return { label: "Abgelehnt", tone: "neutral" };
    case "BLOCKED": return { label: "Nicht ausgeführt", tone: "error" };
    case "EXPIRED": return { label: "Abgelaufen", tone: "neutral" };
    case "CANCELLED": return { label: "Abgebrochen", tone: "neutral" };
    default: return { label: "Ausgang unklar", tone: "wait" };
  }
}

/** German label (tr key) of a risk class. */
export function riskLabel(risk: string | null): string {
  switch (risk) {
    case "READ": return "Lesen";
    case "WRITE": return "Schreiben";
    case "CRITICAL": return "Kritisch";
    default: return "Nicht klassifiziert";
  }
}

/** Only a PENDING card that needs confirmation and is not past its deadline can be decided. */
export function canDecide(card: ToolCallCard, now: number = Date.now()): boolean {
  if (card.status !== "PENDING" || !card.needsConfirmation) return false;
  if (!card.expiresAt) return false;
  const deadline = Date.parse(card.expiresAt);
  return Number.isFinite(deadline) && deadline > now;
}

const MAX_VALUE_CHARS = 400;
const MAX_ENTRIES = 20;

/**
 * Arguments as readable "key: value" lines (plain text, never HTML). Nested values are compact
 * JSON; long values are shortened in the list (the full JSON is available on demand).
 */
export function argumentEntries(args: Record<string, unknown> | null | undefined): { key: string; value: string }[] {
  if (!args || typeof args !== "object" || Array.isArray(args)) return [];
  const out: { key: string; value: string }[] = [];
  for (const [key, raw] of Object.entries(args)) {
    if (out.length >= MAX_ENTRIES) break;
    let value: string;
    if (typeof raw === "string") value = raw;
    else if (raw === null || typeof raw === "number" || typeof raw === "boolean") value = String(raw);
    else {
      try { value = JSON.stringify(raw); } catch { value = "…"; }
    }
    if (value.length > MAX_VALUE_CHARS) value = value.slice(0, MAX_VALUE_CHARS) + " …";
    out.push({ key, value });
  }
  return out;
}

/** Number of arguments not shown because of the entry cap. */
export function hiddenArgumentCount(args: Record<string, unknown> | null | undefined): number {
  if (!args || typeof args !== "object" || Array.isArray(args)) return 0;
  return Math.max(0, Object.keys(args).length - MAX_ENTRIES);
}

/** Cards grouped by the question (user message id) they belong to, in server order. */
export function groupByPrompt(cards: ToolCallCard[]): Map<number, ToolCallCard[]> {
  const out = new Map<number, ToolCallCard[]>();
  for (const c of cards) {
    if (c.promptMessageId == null) continue;
    const list = out.get(c.promptMessageId) ?? [];
    list.push(c);
    out.set(c.promptMessageId, list);
  }
  return out;
}

/**
 * Which question's cards render after transcript row `index`: after the assistant answer that
 * follows the question, or right after the question when no answer row exists (yet).
 */
export function cardsPromptAt(
  messages: { id: number; role: "user" | "assistant" }[],
  index: number,
): number | null {
  const m = messages[index];
  if (!m) return null;
  if (m.role === "assistant") {
    const prev = messages[index - 1];
    return prev && prev.role === "user" ? prev.id : null;
  }
  const next = messages[index + 1];
  return next && next.role === "assistant" ? null : m.id;
}

/** Replace one card by id (decision response), keeping order. */
export function replaceCard(cards: ToolCallCard[], card: ToolCallCard): ToolCallCard[] {
  return cards.map((c) => (c.id === card.id ? card : c));
}
