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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void calculatesEqualAllocationsAndAssignsTheRoundingCent() {
        TwoParticipantReceipt split = twoParticipantReceipt("equal", new BigDecimal("10.01"));

        receiptService.addItemAllocation(split.receiptId(), split.itemId(), split.ownerId(),
                split.ownerParticipantId(), AllocationType.EQUAL, BigDecimal.ONE);
        receiptService.addItemAllocation(split.receiptId(), split.itemId(), split.ownerId(),
                split.friendParticipantId(), AllocationType.EQUAL, BigDecimal.ONE);

        Receipt calculated = receiptService.calculateSplit(split.receiptId(), split.ownerId());

        assertThat(calculated.getItems().getFirst().getAllocations())
                .extracting(allocation -> allocation.getFinalAmount())
                .containsExactlyInAnyOrder(new BigDecimal("5.01"), new BigDecimal("5.00"));
        assertThat(totalOwed(calculated)).isEqualByComparingTo("10.01");
    }

    @Test
    void calculatesPercentageAllocations() {
        TwoParticipantReceipt split = twoParticipantReceipt("percentage", new BigDecimal("10.00"));

        receiptService.addItemAllocation(split.receiptId(), split.itemId(), split.ownerId(),
                split.ownerParticipantId(), AllocationType.PERCENTAGE, new BigDecimal("30"));
        receiptService.addItemAllocation(split.receiptId(), split.itemId(), split.ownerId(),
                split.friendParticipantId(), AllocationType.PERCENTAGE, new BigDecimal("70"));

        Receipt calculated = receiptService.calculateSplit(split.receiptId(), split.ownerId());

        assertThat(calculated.getItems().getFirst().getAllocations())
                .extracting(allocation -> allocation.getFinalAmount())
                .containsExactlyInAnyOrder(new BigDecimal("3.00"), new BigDecimal("7.00"));
        assertThat(totalOwed(calculated)).isEqualByComparingTo("10.00");
    }

    @Test
    void calculatesShareAllocations() {
        TwoParticipantReceipt split = twoParticipantReceipt("shares", new BigDecimal("10.00"));

        receiptService.addItemAllocation(split.receiptId(), split.itemId(), split.ownerId(),
                split.ownerParticipantId(), AllocationType.SHARES, BigDecimal.ONE);
        receiptService.addItemAllocation(split.receiptId(), split.itemId(), split.ownerId(),
                split.friendParticipantId(), AllocationType.SHARES, new BigDecimal("3"));

        Receipt calculated = receiptService.calculateSplit(split.receiptId(), split.ownerId());

        assertThat(calculated.getItems().getFirst().getAllocations())
                .extracting(allocation -> allocation.getFinalAmount())
                .containsExactlyInAnyOrder(new BigDecimal("2.50"), new BigDecimal("7.50"));
        assertThat(totalOwed(calculated)).isEqualByComparingTo("10.00");
    }

    @Test
    void rejectsExactAllocationsThatDoNotCoverTheItemTotal() {
        TwoParticipantReceipt split = twoParticipantReceipt("incomplete-exact", new BigDecimal("10.00"));
        receiptService.addItemAllocation(split.receiptId(), split.itemId(), split.ownerId(),
                split.ownerParticipantId(), AllocationType.EXACT, new BigDecimal("6.00"));
        receiptService.addItemAllocation(split.receiptId(), split.itemId(), split.ownerId(),
                split.friendParticipantId(), AllocationType.EXACT, new BigDecimal("3.00"));

        assertThatThrownBy(() -> receiptService.calculateSplit(split.receiptId(), split.ownerId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Exact allocations must equal the item total");
    }

    @Test
    void rejectsPercentageAllocationsThatDoNotEqualOneHundred() {
        TwoParticipantReceipt split = twoParticipantReceipt("incomplete-percentage", new BigDecimal("10.00"));
        receiptService.addItemAllocation(split.receiptId(), split.itemId(), split.ownerId(),
                split.ownerParticipantId(), AllocationType.PERCENTAGE, new BigDecimal("30"));
        receiptService.addItemAllocation(split.receiptId(), split.itemId(), split.ownerId(),
                split.friendParticipantId(), AllocationType.PERCENTAGE, new BigDecimal("50"));

        assertThatThrownBy(() -> receiptService.calculateSplit(split.receiptId(), split.ownerId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Percentage allocations must equal 100");
    }

    @Test
    void rejectsAnItemWithNoAllocations() {
        TwoParticipantReceipt split = twoParticipantReceipt("no-allocations", new BigDecimal("10.00"));

        assertThatThrownBy(() -> receiptService.calculateSplit(split.receiptId(), split.ownerId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Every item must have at least one allocation");
    }

    @Test
    void rejectsMixedAllocationTypesForAnItem() {
        TwoParticipantReceipt split = twoParticipantReceipt("mixed-types", new BigDecimal("10.00"));
        receiptService.addItemAllocation(split.receiptId(), split.itemId(), split.ownerId(),
                split.ownerParticipantId(), AllocationType.EXACT, new BigDecimal("5.00"));

        assertThatThrownBy(() -> receiptService.addItemAllocation(split.receiptId(), split.itemId(), split.ownerId(),
                split.friendParticipantId(), AllocationType.EQUAL, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same allocation type");
    }

    @Test
    void preservesTheTotalWhenRoundingAnEqualThreeWaySplit() {
        User owner = userRepository.save(user("owner-three-way", "owner-three-way@example.com"));
        User friendOne = userRepository.save(user("friend-one-three-way", "friend-one-three-way@example.com"));
        User friendTwo = userRepository.save(user("friend-two-three-way", "friend-two-three-way@example.com"));
        Receipt receipt = receiptService.createReceipt(owner.getId(), receiptWithOneItem(new BigDecimal("10.00")));
        var participantOne = receiptService.addParticipant(receipt.getId(), owner.getId(), friendOne.getId());
        var participantTwo = receiptService.addParticipant(receipt.getId(), owner.getId(), friendTwo.getId());
        var ownerParticipant = receipt.getParticipants().getFirst();
        Long itemId = receipt.getItems().getFirst().getId();

        receiptService.addItemAllocation(receipt.getId(), itemId, owner.getId(),
                ownerParticipant.getId(), AllocationType.EQUAL, BigDecimal.ONE);
        receiptService.addItemAllocation(receipt.getId(), itemId, owner.getId(),
                participantOne.getId(), AllocationType.EQUAL, BigDecimal.ONE);
        receiptService.addItemAllocation(receipt.getId(), itemId, owner.getId(),
                participantTwo.getId(), AllocationType.EQUAL, BigDecimal.ONE);

        Receipt calculated = receiptService.calculateSplit(receipt.getId(), owner.getId());

        assertThat(calculated.getItems().getFirst().getAllocations())
                .extracting(allocation -> allocation.getFinalAmount())
                .containsExactlyInAnyOrder(new BigDecimal("3.34"), new BigDecimal("3.33"), new BigDecimal("3.33"));
        assertThat(totalOwed(calculated)).isEqualByComparingTo("10.00");
    }

    private TwoParticipantReceipt twoParticipantReceipt(String suffix, BigDecimal itemTotal) {
        User owner = userRepository.save(user("owner-" + suffix, "owner-" + suffix + "@example.com"));
        User friend = userRepository.save(user("friend-" + suffix, "friend-" + suffix + "@example.com"));
        Receipt receipt = receiptService.createReceipt(owner.getId(), receiptWithOneItem(itemTotal));
        var friendParticipant = receiptService.addParticipant(receipt.getId(), owner.getId(), friend.getId());
        return new TwoParticipantReceipt(receipt.getId(), receipt.getItems().getFirst().getId(), owner.getId(),
                receipt.getParticipants().getFirst().getId(), friendParticipant.getId());
    }

    private BigDecimal totalOwed(Receipt receipt) {
        return receipt.getParticipants().stream()
                .map(participant -> participant.getFinalOwedAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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

    private Receipt receiptWithOneItem(BigDecimal itemTotal) {
        Receipt receipt = new Receipt();
        receipt.setDiscount(BigDecimal.ZERO);
        receipt.setTax(BigDecimal.ZERO);
        receipt.setFee(BigDecimal.ZERO);
        receipt.setTip(BigDecimal.ZERO);
        receipt.setTotal(itemTotal);
        receipt.setCurrency("USD");

        ReceiptItem item = new ReceiptItem();
        item.setName("Shared item");
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(itemTotal);
        item.setTotal(itemTotal);
        receipt.getItems().add(item);
        return receipt;
    }

    private record TwoParticipantReceipt(Long receiptId, Long itemId, Long ownerId,
                                         Long ownerParticipantId, Long friendParticipantId) { }
}
