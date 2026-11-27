// Persönliche Verbindungen (TU-06b): Rückkehr aus der OAuth-Anmeldung.
//
// Die Anmeldung läuft wie der OIDC-Login (ADR-0009) im App-Fenster selbst. Der Server-Callback leitet auf die
// feste App-Adresse zurück und hängt einen Einmal-Code im Fragment an (#oauth=<handoff>, bei Fehler #oauth-error).
// Nur die Sitzung, die die Anmeldung gestartet hat, kann ihn einlösen. Das Fragment wird sofort aus der Adresse
// entfernt, damit ein Neuladen es nicht noch einmal einreicht.
import { ref } from "vue";

const PENDING_ORG = "keplura.oauth.pendingOrg";

/** Hinweis für die Verbindungsseite nach der Rückkehr (einmal angezeigt). */
export const connectionNotice = ref<{ ok: boolean } | null>(null);

/** Merkt sich vor der Navigation, in welcher Organisation die Anmeldung gestartet wurde. Nicht sensibel. */
export function rememberConnectionOrg(orgId: number): void {
  try { sessionStorage.setItem(PENDING_ORG, String(orgId)); } catch { /* optional */ }
}

export function takePendingConnectionOrg(): number | null {
  try {
    const raw = sessionStorage.getItem(PENDING_ORG);
    sessionStorage.removeItem(PENDING_ORG);
    const id = raw === null ? NaN : Number(raw);
    return Number.isInteger(id) && id > 0 ? id : null;
  } catch {
    return null;
  }
}

export type ConnectionReturn = { kind: "handoff"; handoff: string } | { kind: "error" } | null;

export function readConnectionFragment(): ConnectionReturn {
  const hash = window.location.hash || "";
  const m = /^#oauth=([0-9a-f]{64})$/.exec(hash);
  if (m) return { kind: "handoff", handoff: m[1] };
  return hash === "#oauth-error" ? { kind: "error" } : null;
}

export function stripConnectionFragment(): void {
  if (window.location.hash.startsWith("#oauth")) {
    history.replaceState(null, "", window.location.pathname + window.location.search);
  }
}
