package com.cs.receipt;

import com.cs.receipt.dto.CreateFriendRequest;
import com.cs.receipt.dto.FriendResponse;
import com.cs.receipt.model.Receipt;
import com.cs.receipt.model.ReceiptItem;
import com.cs.receipt.model.User;
import com.cs.receipt.model.UserStatus;
import com.cs.receipt.service.ReceiptService;
import com.cs.receipt.service.UserService;
import com.cs.receipt.service.FriendService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class FriendSystemIntegrationTests {

    @Autowired
    private UserService userService;

    @Autowired
    private FriendService friendService;

    @Autowired
    private ReceiptService receiptService;

    @Test
    void createsPlaceholderAndInvitedContactsAndAllowsPlaceholdersOnReceipts() {
        User owner = userService.createUser(user("contact-owner", "contact-owner@example.com"));

        FriendResponse placeholder = friendService.addFriend(owner.getId(), friendRequest("Dinner guest", null));
        FriendResponse invited = friendService.addFriend(owner.getId(),
                friendRequest("Future user", "future-user@example.com"));

        assertThat(placeholder.displayName()).isEqualTo("Dinner guest");
        assertThat(placeholder.email()).isNull();
        assertThat(placeholder.status()).isEqualTo(UserStatus.PLACEHOLDER.name());
        assertThat(invited.status()).isEqualTo(UserStatus.INVITED.name());
        assertThat(invited.email()).isEqualTo("future-user@example.com");
        assertThat(friendService.listFriends(owner.getId())).hasSize(2);

        Receipt receipt = receiptService.createReceipt(owner.getId(), receipt());
        var participant = receiptService.addParticipant(receipt.getId(), owner.getId(), placeholder.userId());
        assertThat(participant.getUser().getId()).isEqualTo(placeholder.userId());

        friendService.removeFriend(owner.getId(), placeholder.userId());
        assertThat(friendService.listFriends(owner.getId())).extracting(FriendResponse::userId)
                .containsExactly(invited.userId());
    }

    @Test
    void addsExistingAccountsByUsernameOrEmailWithoutDisplayName() {
        User owner = userService.createUser(user("lookup-owner", "lookup-owner@example.com"));
        User target = userService.createUser(user("lookup-target", "lookup-target@example.com"));
        CreateFriendRequest byUsername = new CreateFriendRequest();
        byUsername.setUsername("  LOOKUP-TARGET  ");
        FriendResponse linked = friendService.addFriend(owner.getId(), byUsername);
        assertThat(linked.userId()).isEqualTo(target.getId());
        assertThat(linked.displayName()).isEqualTo(target.getDisplayName());
        assertThatThrownBy(() -> friendService.addFriend(owner.getId(), byUsername))
                .isInstanceOf(com.cs.receipt.exception.ConflictException.class);
        friendService.removeFriend(owner.getId(), target.getId());
        CreateFriendRequest byEmail = friendRequest(null, "  LOOKUP-TARGET@EXAMPLE.COM  ");
        assertThat(friendService.addFriend(owner.getId(), byEmail).userId()).isEqualTo(target.getId());
        CreateFriendRequest self = new CreateFriendRequest(); self.setUsername("lookup-owner");
        assertThatThrownBy(() -> friendService.addFriend(owner.getId(), self)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingUsernameAndAmbiguousIdentifiersWithoutCreatingPeople() {
        User owner = userService.createUser(user("lookup-invalid-owner", "lookup-invalid@example.com"));
        CreateFriendRequest missing = new CreateFriendRequest(); missing.setUsername("no-such-username");
        assertThatThrownBy(() -> friendService.addFriend(owner.getId(), missing))
                .isInstanceOf(com.cs.receipt.exception.ResourceNotFoundException.class);
        missing.setEmail("someone@example.com");
        assertThatThrownBy(() -> friendService.addFriend(owner.getId(), missing)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> friendService.addFriend(owner.getId(), new CreateFriendRequest()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> friendService.addFriend(owner.getId(), friendRequest(null, "unknown@example.com")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(friendService.listFriends(owner.getId())).isEmpty();
    }

    private User user(String username, String email) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        return user;
    }

    private CreateFriendRequest friendRequest(String displayName, String email) {
        CreateFriendRequest request = new CreateFriendRequest();
        request.setDisplayName(displayName);
        request.setEmail(email);
        return request;
    }

    private Receipt receipt() {
        Receipt receipt = new Receipt();
        receipt.setDiscount(BigDecimal.ZERO);
        receipt.setTax(BigDecimal.ZERO);
        receipt.setFee(BigDecimal.ZERO);
        receipt.setTip(BigDecimal.ZERO);
        receipt.setTotal(new BigDecimal("10.00"));
        receipt.setCurrency("USD");
        ReceiptItem item = new ReceiptItem();
        item.setName("Dinner");
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(new BigDecimal("10.00"));
        item.setTotal(new BigDecimal("10.00"));
        receipt.getItems().add(item);
        return receipt;
    }
}
