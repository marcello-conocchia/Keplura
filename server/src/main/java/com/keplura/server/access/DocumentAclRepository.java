package com.keplura.server.access;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DocumentAclRepository extends JpaRepository<DocumentAcl, Long> {

    List<DocumentAcl> findByDocumentId(Long documentId);

    @Modifying
    @Query("delete from DocumentAcl a where a.documentId = :documentId")
    int deleteByDocumentId(@Param("documentId") Long documentId);
}
