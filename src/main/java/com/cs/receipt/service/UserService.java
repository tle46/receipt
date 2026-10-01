package com.cs.receipt.service;

import com.cs.receipt.model.User;
import com.cs.receipt.repository.UserRepository;
import com.cs.receipt.repository.UserFriendRepository;
import com.cs.receipt.exception.ConflictException;
import com.cs.receipt.exception.ResourceNotFoundException;
import com.cs.receipt.dto.CreateFriendRequest;
import com.cs.receipt.dto.FriendResponse;
import com.cs.receipt.model.UserFriend;
import com.cs.receipt.model.UserStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final UserFriendRepository userFriendRepository;

    public UserService(UserRepository userRepository, UserFriendRepository userFriendRepository) {
        this.userRepository = userRepository;
        this.userFriendRepository = userFriendRepository;
    }

    public User createUser(User user) {

        if (userRepository.existsByUsername(user.getUsername())) {
            throw new ConflictException("Username is already taken");
        }

        if (userRepository.existsByEmail(user.getEmail())) {
            throw new ConflictException("Email is already in use");
        }

        user.setStatus(UserStatus.ACTIVE);
        user.setDisplayName(user.getUsername());
        return userRepository.save(user);
    }

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    @Transactional
    public FriendResponse addFriend(Long userId, CreateFriendRequest request) {
        User user = findUser(userId);
        String email = request.getEmail() == null || request.getEmail().isBlank()
                ? null : request.getEmail().trim().toLowerCase();
        User friend = email == null
                ? createPlaceholder(request.getDisplayName())
                : userRepository.findByEmail(email).orElseGet(() -> createInvitedFriend(request.getDisplayName(), email));

        if (friend.getId().equals(user.getId())) {
            throw new IllegalArgumentException("You cannot add yourself as a friend");
        }
        if (userFriendRepository.findByUserIdAndFriendId(userId, friend.getId()).isPresent()) {
            throw new ConflictException("User is already in your friend list");
        }

        UserFriend friendship = new UserFriend();
        friendship.setUser(user);
        friendship.setFriend(friend);
        return FriendResponse.from(userFriendRepository.save(friendship));
    }

    @Transactional(readOnly = true)
    public List<FriendResponse> listFriends(Long userId) {
        findUser(userId);
        return userFriendRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(FriendResponse::from)
                .toList();
    }

    @Transactional
    public void removeFriend(Long userId, Long friendUserId) {
        findUser(userId);
        UserFriend friendship = userFriendRepository.findByUserIdAndFriendId(userId, friendUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Friend not found in your friend list"));
        userFriendRepository.delete(friendship);
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private User createPlaceholder(String displayName) {
        User placeholder = new User();
        placeholder.setUsername("placeholder-" + UUID.randomUUID());
        placeholder.setDisplayName(displayName);
        placeholder.setStatus(UserStatus.PLACEHOLDER);
        return userRepository.save(placeholder);
    }

    private User createInvitedFriend(String displayName, String email) {
        User invited = new User();
        invited.setUsername("invited-" + UUID.randomUUID());
        invited.setDisplayName(displayName);
        invited.setEmail(email);
        invited.setStatus(UserStatus.INVITED);
        return userRepository.save(invited);
    }
}
