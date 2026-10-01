package com.cs.receipt.repository;

import com.cs.receipt.model.UserFriend;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserFriendRepository extends JpaRepository<UserFriend, Long> {
    List<UserFriend> findByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<UserFriend> findByUserIdAndFriendId(Long userId, Long friendId);
}
