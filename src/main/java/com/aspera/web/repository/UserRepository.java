package com.aspera.web.repository;

import com.aspera.web.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    @EntityGraph(attributePaths = "permissions")
    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    @EntityGraph(attributePaths = "permissions")
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdWithPermissions(@Param("id") long id);

    long countByRole(String role);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.role = 'ADMIN' order by u.id")
    List<User> findAdminsForUpdate();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") long id);
}
