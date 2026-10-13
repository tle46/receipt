package com.cs.receipt.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public class CreateFriendRequest {
    @Size(max = 100, message = "Friend name must be 100 characters or less")
    private String displayName;

    @Email(message = "Email must be valid")
    private String email;

    @Size(max = 100, message = "Username must be 100 characters or less")
    private String username;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
