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

    default List<ToolDefinition> definitionsFor(ToolContext context) { return definitions(context.spaceId()); }

    /**
     * Führt den bereits zentral geprüften Aufruf genau einmal aus. Keine automatische Wiederholung.
     * Muss selbst zeit- und größenbegrenzt sein und darf keine Zugangsdaten im Ergebnis liefern.
     *
     * @throws ToolInvocationException mit generischer, für Nutzer geeigneter Meldung
     */
    String invoke(ToolDefinition tool, JsonNode arguments);

    default String invoke(ToolContext context, ToolDefinition tool, JsonNode arguments) { return invoke(tool, arguments); }

    /** The owning prompt comes only from the server-created card, never from model arguments. */
    default String invoke(ToolContext context, ToolDefinition tool, JsonNode arguments, Long promptMessageId) {
        return invoke(context, tool, arguments);
    }

    /** TU-06b: a server whose tools need the person's own connection, which is missing (shown in the tool menu). */
    record ConnectionHint(String kind, long serverId, String name) { }

    /** Servers of this context with approved tools that are not offered because the person's connection is missing. */
    default List<ConnectionHint> missingConnections(ToolContext context) { return List.of(); }

    /**
     * TU-06d: one file or image from a tool result. {@code invalidReason}: the adapter already knows it is unusable
     * (e.g. invalid encoding, too large to decode); the central store then only notes it on the card.
     */
    record Artifact(String name, String declaredType, byte[] data, String invalidReason) {
        public Artifact(String name, String declaredType, byte[] data) { this(name, declaredType, data, null); }
        @Override public String toString() { return "Artifact[" + declaredType + ", " + (data == null ? 0 : data.length) + " bytes]"; }
    }

    /** Text for the model plus artifacts for the card (TU-06d). */
    record Result(String text, List<Artifact> artifacts) {
        public Result(String text) { this(text, List.of()); }
    }

    /**
     * Like {@link #invoke(ToolContext, ToolDefinition, JsonNode, Long)}, additionally with image/file artifacts.
     * The central service verifies, stores and limits them; the model only gets a placeholder.
     */
    default Result invokeWithArtifacts(ToolContext context, ToolDefinition tool, JsonNode arguments, Long promptMessageId) {
        return new Result(invoke(context, tool, arguments, promptMessageId), List.of());
    }

    /** Reference-only adapters never persist private excerpts in cards or loop checkpoints. */
    default boolean referencesOnly() { return false; }

    record Resolved(String text, String citationsJson, boolean complete) { }

    default Resolved resolve(ToolContext context, ToolDefinition tool, String stored) {
        return new Resolved(stored, "[]", true);
    }

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
