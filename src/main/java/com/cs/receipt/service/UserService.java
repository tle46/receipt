package com.cs.receipt.service;

import com.cs.receipt.exception.ConflictException;
import com.cs.receipt.model.User;
import com.cs.receipt.model.UserStatus;
import com.cs.receipt.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
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

}
