package com.cs.receipt.auth;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.Optional;
public interface AccountTokenRepository extends JpaRepository<AccountToken, String> {
    void deleteByUserIdAndPurpose(Long userId, String purpose);
    void deleteByExpiresAtBefore(java.time.Instant cutoff);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AccountToken> findByHash(String hash);
}
