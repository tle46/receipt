package com.cs.receipt.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Configuration
@EnableScheduling
public class AuthCleanup {
    private final AuthSessionRepository sessions;
    private final AccountTokenRepository tokens;
    public AuthCleanup(AuthSessionRepository sessions, AccountTokenRepository tokens) { this.sessions=sessions; this.tokens=tokens; }
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    @Transactional
    public void removeExpired() {
        Instant now=Instant.now(); tokens.deleteByExpiresAtBefore(now); sessions.deleteByExpiresAtBefore(now);
    }
}
