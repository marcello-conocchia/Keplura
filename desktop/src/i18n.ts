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

/** One text from the server (German) in the current UI language. Unknown texts stay as they are. */
export function serverText(text: string): string {
  if (locale.value !== "en" || !text) return text;
  const exact = SERVER_EN[text] ?? EN[text];
  if (exact !== undefined) return exact;
  for (const [pattern, english] of SERVER_EN_PATTERNS) {
    if (pattern.test(text)) return text.replace(pattern, english);
  }
  return text;
}

/**
 * Translates every string in a parsed API response that is a known server text.
 * Applied where admin/data-source/productivity responses are read; values that
 * are user data (names, paths) do not match the keys and stay unchanged.
 */
export function translateServer<T>(value: T): T {
  if (locale.value !== "en") return value;
  return walk(value, 0) as T;
}

function walk(v: unknown, depth: number): unknown {
  if (typeof v === "string") return serverText(v);
  if (depth > 8 || v === null || typeof v !== "object") return v;
  if (Array.isArray(v)) return v.map((x) => walk(x, depth + 1));
  const out: Record<string, unknown> = {};
  for (const [k, x] of Object.entries(v as Record<string, unknown>)) out[k] = walk(x, depth + 1);
  return out;
}
