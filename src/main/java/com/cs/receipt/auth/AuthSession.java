package com.cs.receipt.auth;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
public class AuthSession {
    @Id public String id;
    @Column(nullable = false) public Long userId;
    @Column(nullable = false, unique = true) public String refreshHash;
    @Column(nullable = false) public Instant expiresAt;
    public boolean revoked;
    @Version public long version;
}
