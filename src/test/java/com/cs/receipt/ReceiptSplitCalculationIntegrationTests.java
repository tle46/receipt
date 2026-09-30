package com.cs.receipt;

import com.cs.receipt.model.AllocationType;
import com.cs.receipt.model.Receipt;
import com.cs.receipt.model.ReceiptItem;
import com.cs.receipt.model.User;
import com.cs.receipt.repository.UserRepository;
import com.cs.receipt.service.ReceiptService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ReceiptSplitCalculationIntegrationTests {

    @Autowired
    private ReceiptService receiptService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void calculatesItemAllocationsAndReceiptChargesWithoutLosingCents() {
        // Add Alex and Sam
        User owner = userRepository.save(user("alex", "alex@example.com"));
        User friend = userRepository.save(user("sam", "sam@example.com"));

        // Create $10 receipt (12 including tax)
        Receipt receipt = receiptService.createReceipt(owner.getId(), receiptWithOneTenDollarItem());

        // Add participant to receipt
        var friendParticipant = receiptService.addParticipant(receipt.getId(), owner.getId(), friend.getId());

        var ownerParticipant = receipt.getParticipants().getFirst();
        Long itemId = receipt.getItems().getFirst().getId();

        // Allocate 6 dollars to alex
        receiptService.addItemAllocation(receipt.getId(), itemId, owner.getId(),
                ownerParticipant.getId(), AllocationType.EXACT, new BigDecimal("6.00"));

        // Allocate 4 dollars to sam
        receiptService.addItemAllocation(receipt.getId(), itemId, owner.getId(),
                friendParticipant.getId(), AllocationType.EXACT, new BigDecimal("4.00"));

        Receipt calculated = receiptService.calculateSplit(receipt.getId(), owner.getId());

        // 7.2 4.8 split of 12
        assertThat(calculated.getParticipants()).extracting(participant -> participant.getFinalOwedAmount())
                .containsExactlyInAnyOrder(new BigDecimal("7.20"), new BigDecimal("4.80"));

        // 6 4 split of 10
        assertThat(calculated.getItems().getFirst().getAllocations())
                .extracting(allocation -> allocation.getFinalAmount())
                .containsExactlyInAnyOrder(new BigDecimal("6.00"), new BigDecimal("4.00"));

        // Totals 12
        assertThat(calculated.getParticipants().stream()
                .map(participant -> participant.getFinalOwedAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("12.00");
    }

    private User user(String username, String email) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        return user;
    }

    private Receipt receiptWithOneTenDollarItem() {
        Receipt receipt = new Receipt();
        receipt.setDiscount(new BigDecimal("1.00"));
        receipt.setTax(new BigDecimal("0.80"));
        receipt.setFee(new BigDecimal("0.20"));
        receipt.setTip(new BigDecimal("2.00"));
        receipt.setTotal(new BigDecimal("12.00"));
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
