package com.keplura.server.access;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserPrincipalRepository extends JpaRepository<UserPrincipal, Long> {

    List<UserPrincipal> findByUserId(Long userId);

    @Modifying
    @Query("delete from UserPrincipal p where p.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);

    /** Directory-managed principals only (sid:, posix-*, ldap-dn:) — Entra entries stay. */
    @Modifying
    @Query("delete from UserPrincipal p where p.userId = :userId and p.principal not like 'entra%'")
    int deleteDirectoryPrincipals(@Param("userId") Long userId);

    /** Entra-managed principals only (entra:<object-id>). */
    @Modifying
    @Query("delete from UserPrincipal p where p.userId = :userId and p.principal like 'entra%'")
    int deleteEntraPrincipals(@Param("userId") Long userId);
}
