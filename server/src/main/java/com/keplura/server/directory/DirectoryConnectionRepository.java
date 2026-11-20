package com.keplura.server.directory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DirectoryConnectionRepository extends JpaRepository<DirectoryConnection, Long> {

    List<DirectoryConnection> findAllByOrderByIdAsc();

    List<DirectoryConnection> findByEnabledTrueAndLoginEnabledTrueOrderByIdAsc();
}
