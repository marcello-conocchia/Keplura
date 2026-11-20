# ADR-0011: Browser-first — Web-Oberfläche vom Server ausgeliefert, Desktop-App bleibt

Date: 2026-11-14
Status: Accepted (PO-Entscheidung 14.11.2026: „auch für Browser, Browser first")
Amends: ADR-0001 (dort: „Browser web app as the primary desktop product" abgelehnt)

## Entscheidung

1. Dieselbe Vue-3-App wird zusätzlich als Web-Bundle gebaut
   (`VITE_SERVER_URL=same-origin`) und vom Spring-Boot-Server unter `/`
   ausgeliefert (`classpath:/static`, Build-Stufe in `deploy/Dockerfile.server`).
2. Same-Origin: Browser → `https://<host>/` und `/api/...` am selben Ursprung;
   Session-Cookie (HttpOnly, Secure, SameSite=Lax) und CSRF-Double-Submit
   funktionieren ohne CORS-Freigaben. Der TLS-Reverse-Proxy bleibt Pflicht.
3. Gestaltung erfolgt Browser-first (responsiv bis Tablet/Laptop); Tauri-only-
   Funktionen (Vollbild per F11) bleiben optionale Desktop-Ergänzungen.
4. Die Desktop-App (Tauri, ADR-0005-Transport) bleibt unverändert lieferbar.

## Konsequenzen

- Kundeninstallation ohne Client-Rollout möglich (schneller Pilot).
- Windows-MSI/Signing wird vom Blocker zum optionalen Zusatz.
- Server-Image braucht eine Node-Build-Stufe; das Desktop-Paket bleibt getrennt.
- Content-Security-Policy für die Web-Auslieferung ist ein Folgepunkt
  (Desktop-CSP ist heute `null`, siehe PROJECT_STATE-Lücken).
