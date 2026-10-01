package com.cs.receipt.dto;

public class UserResponse {

    private Long id;
    private String username;
    private String email;
    private String displayName;
    private String status;

    public UserResponse() {
    }

    public UserResponse(Long id, String username, String email, String displayName, String status) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.displayName = displayName;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() { return displayName; }
    public String getStatus() { return status; }
}
