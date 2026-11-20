/** UI-REFRESH-1: shadcn-vue-Grundgeruest-Helfer — Klassen zusammenfuegen.
 *  Bewusst ohne clsx/tailwind-merge (keine neuen JS-Schwerpunkte laut Karte):
 *  Phase-1-Klassen sind konfliktfrei, einfache Konkatenation reicht. */
export function cn(...parts: Array<string | false | null | undefined | unknown>): string {
  return parts.filter((p): p is string => typeof p === "string" && p.length > 0).join(" ");
}
