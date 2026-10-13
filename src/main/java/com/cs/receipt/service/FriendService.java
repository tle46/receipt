package com.cs.receipt.service;

import com.cs.receipt.dto.CreateFriendRequest;
import com.cs.receipt.dto.FriendResponse;
import com.cs.receipt.exception.ConflictException;
import com.cs.receipt.exception.ResourceNotFoundException;
import com.cs.receipt.model.User;
import com.cs.receipt.model.UserFriend;
import com.cs.receipt.model.UserStatus;
import com.cs.receipt.repository.UserFriendRepository;
import com.cs.receipt.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class FriendService {
    private final UserRepository userRepository;
    private final UserFriendRepository userFriendRepository;

    public FriendService(UserRepository userRepository, UserFriendRepository userFriendRepository) {
        this.userRepository = userRepository;
        this.userFriendRepository = userFriendRepository;
    }

    @Transactional
    public FriendResponse addFriend(Long userId, CreateFriendRequest request) {
        User user = findUser(userId);
        String email = normalized(request.getEmail());
        String username = normalized(request.getUsername());
        if (email != null && username != null) {
            throw new IllegalArgumentException("Provide either username or email, not both");
        }
        User friend;
        if (username != null) {
            friend = userRepository.findByUsernameIgnoreCase(username)
                    .orElseThrow(() -> new ResourceNotFoundException("User with that username not found"));
        } else if (email != null) {
            friend = userRepository.findByEmailIgnoreCase(email)
                    .orElseGet(() -> createInvitedFriend(requiredDisplayName(request), email));
        } else {
            friend = createPlaceholder(requiredDisplayName(request));
        }

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

    private String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String requiredDisplayName(CreateFriendRequest request) {
        if (request.getDisplayName() == null || request.getDisplayName().isBlank()) {
            throw new IllegalArgumentException("Friend name is required to create a new person");
        }
        return request.getDisplayName().trim();
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
