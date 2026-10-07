package com.cs.receipt.auth;

import com.cs.receipt.model.User;

public record AccountResponse(Long id, String username, String email, String displayName, String status,
                              boolean emailVerified, boolean passwordEnabled, boolean googleLinked) {
    public static AccountResponse from(User user) {
        return new AccountResponse(user.getId(), user.getUsername(), user.getEmail(), user.getDisplayName(),
                user.getStatus().name(), user.isEmailVerified(), user.getPasswordHash() != null,
                user.getGoogleSubject() != null);
    }
}
