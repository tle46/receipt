package com.cs.receipt.dto;

import com.cs.receipt.model.User;
import com.cs.receipt.model.UserFriend;

public record FriendResponse(Long id, Long userId, String displayName, String email, String status) {
    public static FriendResponse from(UserFriend friendship) {
        User friend = friendship.getFriend();
        return new FriendResponse(friendship.getId(), friend.getId(), friend.getDisplayName(),
                friend.getEmail(), friend.getStatus().name());
    }
}
