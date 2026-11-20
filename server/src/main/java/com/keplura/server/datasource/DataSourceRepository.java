package com.keplura.server.datasource;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DataSourceRepository extends JpaRepository<DataSource, Long> {
    List<DataSource> findAllByOrderByIdAsc();
    List<DataSource> findByEnabledTrue();
}
