package com.cs.receipt.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CreateFriendRequest {
    @NotBlank(message = "Friend name is required")
    @Size(max = 100, message = "Friend name must be 100 characters or less")
    private String displayName;

    @Email(message = "Email must be valid")
    private String email;

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
