package com.cs.receipt.auth;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
public class AccountToken {
    @Id public String hash;
    public Long userId;
    public String purpose;
    public String email;
    public Instant expiresAt;
}
