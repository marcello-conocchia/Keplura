package com.keplura.server.tool;

import tools.jackson.databind.JsonNode;

/**
 * Ein Werkzeug, wie ein {@link ToolAdapter} es meldet.
 *
 * @param id           stabile Identität, z. B. {@code mcp:12:echo} (Adapter, Quelle, Werkzeugname)
 * @param adapter      Adapterkennung, z. B. {@code mcp}
 * @param functionName Name für das Modell, {@code ^[a-zA-Z0-9_-]{1,64}$}, eindeutig je Katalog
 * @param toolName     Name des Werkzeugs bei der Quelle
 * @param sourceId     Quelle innerhalb des Adapters (z. B. MCP-Server-ID), sonst {@code null}
 * @param sourceName   Anzeigename der Quelle
 * @param description  Beschreibung (Daten, keine Anweisung)
 * @param inputSchema  Eingabeschema (JSON Schema, unterstützte Teilmenge siehe {@link ToolArgumentValidator})
 * @param risk         vom Admin gesetzte Risikoklasse, {@code null} = unklassifiziert
 * @param approved     ausdrückliche Adminfreigabe des Werkzeugs für diese Klasse
 * @param fingerprint  Ausführungsziel/Zugang; ändert er sich, gilt die Definition als neu (Vergleich vor Ausführung)
 */
public record ToolDefinition(String id, String adapter, String functionName, String toolName, Long sourceId,
                             String sourceName, String description, JsonNode inputSchema, ToolRisk risk,
                             boolean approved, String fingerprint, boolean internal) {
    public ToolDefinition(String id, String adapter, String functionName, String toolName, Long sourceId,
                          String sourceName, String description, JsonNode inputSchema, ToolRisk risk,
                          boolean approved, String fingerprint) {
        this(id, adapter, functionName, toolName, sourceId, sourceName, description, inputSchema, risk, approved, fingerprint, false);
    }
}
