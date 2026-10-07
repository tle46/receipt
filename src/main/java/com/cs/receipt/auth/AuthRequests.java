package com.cs.receipt.auth;

import jakarta.validation.constraints.*;

public final class AuthRequests {
    private AuthRequests() {}
    public record Register(@NotBlank @Pattern(regexp="[a-zA-Z0-9_]{3,32}") String username,
                           @NotBlank @Size(min=12, max=72) String password,
                           @NotBlank @Email @Size(max=254) String email,
                           @NotBlank @Size(max=80) String displayName) {}
    public record Login(@NotBlank @Size(max=32) String username, @NotBlank @Size(max=72) String password) {}
    public record Refresh(@NotBlank @Size(max=128) String refreshToken) {}
    public record Google(@NotBlank @Size(max=8192) String idToken, @NotBlank @Size(max=128) String nonce) {}
    public record EmailRequest(@NotBlank @Email @Size(max=254) String email) {}
    public record Token(@NotBlank @Size(max=128) String token) {}
    public record Reset(@NotBlank @Size(max=128) String token, @NotBlank @Size(min=12,max=72) String password) {}
    public record Password(@NotBlank @Size(max=72) String currentPassword, @NotBlank @Size(min=12,max=72) String newPassword) {}
    public record Profile(@NotBlank @Size(max=80) String displayName) {}
}
