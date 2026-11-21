package com.keplura.server.tool;

/** Serverseitig bewiesener Aufrufkontext: Benutzer, Organisation, Arbeitsbereich und Chat. */
public record ToolContext(Long userId, Long orgId, Long spaceId, Long conversationId) {
}
