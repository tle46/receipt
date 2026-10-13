package com.cs.receipt.controller;

import com.cs.receipt.dto.CreateFriendRequest;
import com.cs.receipt.dto.FriendResponse;
import com.cs.receipt.dto.BalanceResponse;
import com.cs.receipt.dto.SettlePersonResponse;
import com.cs.receipt.service.BalanceService;

import com.cs.receipt.service.FriendService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "Manage the authenticated user's contacts, balances, and payments")
public class UserController {

    private final FriendService friendService;
    private final BalanceService balanceService;

    public UserController(FriendService friendService, BalanceService balanceService) {

        this.friendService = friendService;
        this.balanceService = balanceService;
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

    @GetMapping("/{userId}/balances")
    public BalanceResponse balances(@PathVariable Long userId) {
        return balanceService.getBalances(userId);
    }

    @PostMapping("/{userId}/balances/people/{personUserId}/settle")
    public SettlePersonResponse settlePerson(@PathVariable Long userId,
                                                             @PathVariable Long personUserId) {
        return balanceService.settlePerson(userId, personUserId);
    }
}
