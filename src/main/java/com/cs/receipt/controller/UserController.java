package com.cs.receipt.controller;

import com.cs.receipt.dto.CreateFriendRequest;
import com.cs.receipt.dto.FriendResponse;

import com.cs.receipt.service.FriendService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "Manage the authenticated user's contacts")
public class UserController {

    private final FriendService friendService;

    public UserController(FriendService friendService) {

        this.friendService = friendService;
    }

    @PostMapping("/{userId}/friends")
    @ResponseStatus(HttpStatus.CREATED)
    public FriendResponse addFriend(@PathVariable Long userId,
                                    @Valid @RequestBody CreateFriendRequest request) {
        return friendService.addFriend(userId, request);
    }

    @GetMapping("/{userId}/friends")
    public List<FriendResponse> listFriends(@PathVariable Long userId) {
        return friendService.listFriends(userId);
    }

    @DeleteMapping("/{userId}/friends/{friendUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFriend(@PathVariable Long userId, @PathVariable Long friendUserId) {
        friendService.removeFriend(userId, friendUserId);
    }

}
