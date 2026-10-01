package com.cs.receipt;

import com.cs.receipt.dto.CreateFriendRequest;
import com.cs.receipt.dto.FriendResponse;
import com.cs.receipt.model.Receipt;
import com.cs.receipt.model.ReceiptItem;
import com.cs.receipt.model.User;
import com.cs.receipt.model.UserStatus;
import com.cs.receipt.service.ReceiptService;
import com.cs.receipt.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FriendSystemIntegrationTests {

    @Autowired
    private UserService userService;

    @Autowired
    private ReceiptService receiptService;

    @Test
    void createsPlaceholderAndInvitedContactsAndAllowsPlaceholdersOnReceipts() {
        User owner = userService.createUser(user("contact-owner", "contact-owner@example.com"));

        FriendResponse placeholder = userService.addFriend(owner.getId(), friendRequest("Dinner guest", null));
        FriendResponse invited = userService.addFriend(owner.getId(),
                friendRequest("Future user", "future-user@example.com"));

        assertThat(placeholder.displayName()).isEqualTo("Dinner guest");
        assertThat(placeholder.email()).isNull();
        assertThat(placeholder.status()).isEqualTo(UserStatus.PLACEHOLDER.name());
        assertThat(invited.status()).isEqualTo(UserStatus.INVITED.name());
        assertThat(invited.email()).isEqualTo("future-user@example.com");
        assertThat(userService.listFriends(owner.getId())).hasSize(2);

        Receipt receipt = receiptService.createReceipt(owner.getId(), receipt());
        var participant = receiptService.addParticipant(receipt.getId(), owner.getId(), placeholder.userId());
        assertThat(participant.getUser().getId()).isEqualTo(placeholder.userId());

        userService.removeFriend(owner.getId(), placeholder.userId());
        assertThat(userService.listFriends(owner.getId())).extracting(FriendResponse::userId)
                .containsExactly(invited.userId());
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
