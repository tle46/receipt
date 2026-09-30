package com.cs.receipt.controller;

import com.cs.receipt.dto.CreateUserRequest;
import com.cs.receipt.dto.UserResponse;
import com.cs.receipt.model.User;
import com.cs.receipt.service.UserService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "Create receipt owners and participants")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public UserResponse createUser(
            @Valid @RequestBody CreateUserRequest request) {

        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());

        User savedUser = userService.createUser(user);

        return new UserResponse(
                savedUser.getId(),
                savedUser.getUsername(),
                savedUser.getEmail()
        );
    }

}
