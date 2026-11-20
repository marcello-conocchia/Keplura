package com.keplura.server.access;

/**
 * ADR-0012 §3 — the ONE read rule for data-source documents, as reusable query
 * fragments. It runs INSIDE every statement that can reveal a document (search,
 * preview, list, download), before ranking — never as a Java post-filter.
 *
 * <p>Rule: a document WITHOUT acl rows (manual upload) is visible to every
 * member of its knowledge base (unchanged behaviour). A document WITH rows is
 * visible only if at least one ALLOW entry matches the user's principals (or
 * 'everyone') AND no DENY entry matches. Fail-closed: a data-source file whose
 * rights could not be determined gets a single deny-'everyone' row.
 */
public final class AccessFilter {

    private AccessFilter() {
    }

    /** Special principal: every signed-in person. */
    public static final String EVERYONE = "everyone";

    /**
     * Native SQL; expects the document id column as {@code DOC} (replace) and
     * binds the user id TWICE (allow branch, deny branch), in that order.
     */
    public static String sql(String documentIdColumn) {
        return """
                (not exists (select 1 from kb_document_acl a where a.document_id = DOC)
                  or (exists (select 1 from kb_document_acl a where a.document_id = DOC and a.allow_read = true
                              and (a.principal = 'everyone'
                                   or a.principal in (select p.principal from user_principal p where p.user_id = ?)))
                      and not exists (select 1 from kb_document_acl a where a.document_id = DOC and a.allow_read = false
                              and (a.principal = 'everyone'
                                   or a.principal in (select p.principal from user_principal p where p.user_id = ?)))))
                """.replace("DOC", documentIdColumn);
    }

    /** JPQL for queries over {@code Document d} (named parameter {@code :userId}); compile-time constant. */
    public static final String JPQL_DOCUMENT = """
            (not exists (select 1 from DocumentAcl a where a.documentId = d.id)
              or (exists (select 1 from DocumentAcl a where a.documentId = d.id and a.allowRead = true
                          and (a.principal = 'everyone'
                               or a.principal in (select p.principal from UserPrincipal p where p.userId = :userId)))
                  and not exists (select 1 from DocumentAcl a where a.documentId = d.id and a.allowRead = false
                          and (a.principal = 'everyone'
                               or a.principal in (select p.principal from UserPrincipal p where p.userId = :userId)))))
            """;

    /** JPQL for queries over {@code Chunk c} (named parameter {@code :userId}); compile-time constant. */
    public static final String JPQL_CHUNK = """
            (not exists (select 1 from DocumentAcl a where a.documentId = c.documentId)
              or (exists (select 1 from DocumentAcl a where a.documentId = c.documentId and a.allowRead = true
                          and (a.principal = 'everyone'
                               or a.principal in (select p.principal from UserPrincipal p where p.userId = :userId)))
                  and not exists (select 1 from DocumentAcl a where a.documentId = c.documentId and a.allowRead = false
                          and (a.principal = 'everyone'
                               or a.principal in (select p.principal from UserPrincipal p where p.userId = :userId)))))
            """;
}
