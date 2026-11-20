package com.keplura.server.space;

/**
 * Published synchronously INSIDE the space-delete transaction, before the
 * space row goes. Listeners in dependent modules (knowledge) remove their
 * data in the same transaction — the space package never depends on them.
 */
public record SpaceDeletingEvent(Long orgId, Long spaceId) {
}
