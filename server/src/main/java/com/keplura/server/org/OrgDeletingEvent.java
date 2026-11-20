package com.keplura.server.org;

/**
 * Published synchronously INSIDE the org-delete transaction before the org
 * row goes; dependent modules (space → knowledge) remove their data in the
 * same transaction, so the org package never depends on them.
 */
public record OrgDeletingEvent(Long orgId) {
}
