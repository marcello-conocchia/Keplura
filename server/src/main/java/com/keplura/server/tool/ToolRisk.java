package com.keplura.server.tool;

import java.util.Locale;

/**
 * Serverseitige Risikoklasse eines Werkzeugs (TU-01). Nur ein Admin setzt sie; weder Modell,
 * Dokument, Tool-Ergebnis noch Connector (z. B. MCP-Annotationen) dürfen sie setzen oder absenken.
 * Ein Werkzeug ohne Klasse ist gesperrt.
 */
public enum ToolRisk {
    /** Liest nur. Ausführbar nach ausdrücklicher Adminfreigabe. */
    READ,
    /** Ändert externe Systeme. Standardmäßig aus; nur mit eigener ausdrücklicher Adminfreigabe. */
    WRITE,
    /** Folgenreiche Aktion. Braucht zusätzlich eine konkrete Nutzerbestätigung (ab TU-02) — bis dahin gesperrt. */
    CRITICAL;

    /** Unbekannte oder leere Werte ergeben {@code null} (= unklassifiziert, gesperrt). */
    public static ToolRisk parse(String value) {
        if (value == null) return null;
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
