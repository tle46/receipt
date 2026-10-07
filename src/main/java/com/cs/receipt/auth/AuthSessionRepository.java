package com.cs.receipt.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.*;

public interface AuthSessionRepository extends JpaRepository<AuthSession, String> {
    void deleteByExpiresAtBefore(java.time.Instant cutoff);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AuthSession> findByRefreshHash(String hash);
    List<AuthSession> findByUserId(Long userId);
}
