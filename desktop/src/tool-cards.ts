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
  /**
   * Exactly the stored JSON text that runs on confirmation — shown verbatim, never parsed in the
   * client (JSON.parse would round integers beyond 2^53). null = not stored (blocked plan only).
   */
  argumentsText: string | null;
  /** Same inputs per field, values already lossless text from the server. */
  argumentFields: { name: string; value: string }[];
  status: ToolCallStatus;
  needsConfirmation: boolean;
  /** Tool output — foreign content, rendered as plain text only. */
  result: string | null;
  /** Generic server message (German key, translated via serverText). */
  error: string | null;
  createdAt: string;
  expiresAt: string | null;
  /** Server-bound first confirmed action; absent for initial and READ calls. */
  parentCallId?: string | null;
  parentTool?: string | null;
  mayContainInternal?: boolean;
  sourceUnavailable?: boolean;
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
  if (card.sourceUnavailable || card.status !== "PENDING" || !card.needsConfirmation) return false;
  if (!card.expiresAt) return false;
  const deadline = Date.parse(card.expiresAt);
  return Number.isFinite(deadline) && deadline > now;
}

const MAX_VALUE_CHARS = 400;
const MAX_ENTRIES = 20;

/**
 * Short summary of the inputs as "key: value" lines (plain text, never HTML). Values come as
 * lossless text from the server; long values are shortened and marked. The summary is never the
 * basis of a decision: the exact full text (argumentsText) is always available on the card.
 */
export function argumentEntries(
  fields: { name: string; value: string }[] | null | undefined,
): { key: string; value: string; truncated: boolean }[] {
  if (!Array.isArray(fields)) return [];
  return fields.slice(0, MAX_ENTRIES).map((f) => {
    const value = typeof f.value === "string" ? f.value : String(f.value);
    const truncated = value.length > MAX_VALUE_CHARS;
    return { key: String(f.name), value: truncated ? value.slice(0, MAX_VALUE_CHARS) + " …" : value, truncated };
  });
}

/** Number of fields left out of the summary because of the entry cap. */
export function hiddenArgumentCount(fields: { name: string; value: string }[] | null | undefined): number {
  return Array.isArray(fields) ? Math.max(0, fields.length - MAX_ENTRIES) : 0;
}

/** True when the summary does not show everything (more fields or shortened values). */
export function summaryIsPartial(fields: { name: string; value: string }[] | null | undefined): boolean {
  return hiddenArgumentCount(fields) > 0 || argumentEntries(fields).some((e) => e.truncated);
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
