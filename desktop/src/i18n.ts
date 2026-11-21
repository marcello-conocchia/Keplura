import { ref, watchEffect } from "vue";
import { EN } from "./i18n-en";

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
