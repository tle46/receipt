package com.cs.receipt.repository;

import com.cs.receipt.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.id = :id")
    Optional<User> findForAuthentication(@org.springframework.data.repository.query.Param("id") Long id);

    Optional<User> findByUsernameIgnoreCase(String username);
    Optional<User> findByGoogleSubject(String subject);
    Optional<User> findByEmail(String email);
    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmail(String email);

    boolean existsByUsername(String username);
}
