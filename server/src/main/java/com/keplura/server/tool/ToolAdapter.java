package com.keplura.server.tool;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Adaptervertrag (TU-01). Ein Adapter kennt nur seinen Transport und seine Admin-Konfiguration.
 * Er entscheidet NICHT über Ausführung: Kontext, Freigabe, Risikoklasse, Schema, Limits und Audit
 * prüft ausschließlich {@link ToolExecutionService}. Adapter werden nie direkt vom Chat aufgerufen.
 */
public interface ToolAdapter {

    /** Adapterkennung, z. B. {@code mcp}. */
    String kind();

    /**
     * Alle Werkzeuge, die im Arbeitsbereich grundsätzlich angebunden sind — inklusive gesperrter,
     * unklassifizierter oder nicht freigegebener. Der zentrale Dienst filtert. Liest jedes Mal frisch.
     */
    List<ToolDefinition> definitions(Long spaceId);

    /**
     * Führt den bereits zentral geprüften Aufruf genau einmal aus. Keine automatische Wiederholung.
     * Muss selbst zeit- und größenbegrenzt sein und darf keine Zugangsdaten im Ergebnis liefern.
     *
     * @throws ToolInvocationException mit generischer, für Nutzer geeigneter Meldung
     */
    String invoke(ToolDefinition tool, JsonNode arguments);

    /** Fehler beim externen Aufruf; die Meldung darf an Modell und Audit gehen (keine Geheimnisse). */
    class ToolInvocationException extends RuntimeException {
        public ToolInvocationException(String safeMessage) {
            super(safeMessage);
        }
    }

    /** Der externe Aufruf wurde nachweislich NICHT begonnen (z. B. Ziel geändert, Zugang nicht lesbar). */
    class ToolNotStartedException extends ToolInvocationException {
        public ToolNotStartedException(String safeMessage) {
            super(safeMessage);
        }
    }
}
