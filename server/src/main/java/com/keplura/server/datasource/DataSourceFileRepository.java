package com.keplura.server.datasource;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DataSourceFileRepository extends JpaRepository<DataSourceFile, Long> {
    List<DataSourceFile> findByDataSourceId(Long dataSourceId);

    java.util.Optional<DataSourceFile> findFirstByDocumentId(Long documentId);
}
