import { ref, watchEffect } from "vue";
import { EN } from "./i18n-en";
import { SERVER_EN, SERVER_EN_PATTERNS } from "./i18n-server-en";

/**
 * UI language (German / English). The German text is the key: `tr("Abbrechen")`
 * returns it unchanged in German and looks it up in {@link EN} in English.
 * Placeholders are positional: `tr("{0} von {1}", a, b)`.
 *
 * Switching is live: `tr` reads the reactive `locale`, so templates and computeds
 * re-render. Strings evaluated once at module load would not, so keep labels in
 * functions or computeds.
 */
export type Locale = "de" | "en";

const STORAGE_KEY = "keplura.locale";

function initial(): Locale {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw === "de" || raw === "en") return raw;
  } catch {
    // hardened webviews may block storage; fall back to the system language
  }
  const nav = typeof navigator !== "undefined" ? navigator.language || "" : "";
  if (!nav) return "de";
  return nav.toLowerCase().startsWith("de") ? "de" : "en";
}

export const locale = ref<Locale>(initial());

watchEffect(() => {
  if (typeof document !== "undefined") document.documentElement.lang = locale.value;
});

export function setLocale(next: Locale) {
  locale.value = next;
  try {
    localStorage.setItem(STORAGE_KEY, next);
  } catch {
    // not persisted; the choice still applies until restart
  }
}

export function tr(german: string, ...args: (string | number | null | undefined)[]): string {
  const text = locale.value === "en" ? (EN[german] ?? german) : german;
  return args.length === 0 ? text : text.replace(/\{(\d+)\}/g, (m, i) => {
    const v = args[Number(i)];
    return v === undefined || v === null ? "" : String(v);
  });
}

/** BCP-47 tag for dates and numbers in the current UI language. */
export function dateLocale(): string {
  return locale.value === "en" ? "en-GB" : "de-DE";
}

/**
 * One text from the server (German) in the current UI language. Unknown texts stay as they are.
 * {@code generic=false} skips the catch-all patterns ("… fehlt.", "… ist zu lang."), which only
 * fit validation errors.
 */
export function serverText(text: string, generic = true): string {
  if (locale.value !== "en" || !text) return text;
  const exact = SERVER_EN[text];
  if (exact !== undefined) return exact;
  for (const [pattern, english, isGeneric] of SERVER_EN_PATTERNS) {
    if (isGeneric && !generic) continue;
    if (pattern.test(text)) return text.replace(pattern, english);
  }
  return text;
}

/** Exact match only, e.g. for a fixed chat answer the server sends. */
export function serverExact(text: string): string {
  return locale.value === "en" ? SERVER_EN[text] ?? text : text;
}

/** Response fields that carry server text; everything else (names, paths, locations) is user data. */
const MESSAGE_KEYS = new Set(["message", "error", "reason", "contentReason", "lastRunMessage", "lastError",
  "problems"]);
/** Server texts that sit next to user data under the same key (e.g. a template "title"): no catch-all patterns. */
const TEXT_KEYS = new Set(["detail", "title"]);
/** Fields with fixed server labels: exact matches only, no patterns. */
const LABEL_KEYS = new Set(["label", "categories", "broadGroups"]);

/**
 * Translates the server-text fields of a parsed API response (see MESSAGE_KEYS/LABEL_KEYS).
 * Applied where admin/data-source/productivity responses are read. Other fields such as
 * relativePath, folder, displayName or location are never touched, so values sent back to
 * the server stay exactly as the server gave them.
 */
export function translateServer<T>(value: T): T {
  if (locale.value !== "en") return value;
  return walk(value, 0, null) as T;
}

type Mode = "message" | "text" | "label" | null;

function walk(v: unknown, depth: number, mode: Mode): unknown {
  if (typeof v === "string") {
    if (mode === "message") return serverText(v);
    if (mode === "text") return serverText(v, false);
    if (mode === "label") return SERVER_EN[v] ?? v;
    return v;
  }
  if (depth > 8 || v === null || typeof v !== "object") return v;
  if (Array.isArray(v)) return v.map((x) => walk(x, depth + 1, mode));
  return Object.fromEntries(Object.entries(v as Record<string, unknown>).map(([k, x]) =>
    [k, walk(x, depth + 1,
      MESSAGE_KEYS.has(k) ? "message" : TEXT_KEYS.has(k) ? "text" : LABEL_KEYS.has(k) ? "label" : null)]));
}
