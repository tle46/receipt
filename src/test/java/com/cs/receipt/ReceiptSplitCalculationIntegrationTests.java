package com.cs.receipt;

import com.cs.receipt.model.AllocationType;
import com.cs.receipt.dto.ReceiptResponse;
import com.cs.receipt.dto.SaveReceiptDraftAllocationRequest;
import com.cs.receipt.dto.SaveReceiptDraftItemRequest;
import com.cs.receipt.dto.SaveReceiptDraftRequest;
import com.cs.receipt.dto.SaveReceiptDraftAdjustmentAllocationRequest;
import com.cs.receipt.model.ReceiptAdjustmentType;
import com.cs.receipt.exception.ResourceNotFoundException;
import com.cs.receipt.exception.ForbiddenOperationException;
import com.cs.receipt.model.Receipt;
import com.cs.receipt.model.ReceiptItem;
import com.cs.receipt.model.ReceiptStatus;
import com.cs.receipt.model.User;
import com.cs.receipt.repository.UserRepository;
import com.cs.receipt.service.ReceiptService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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

    @Test
    void finalizesReopensAndSettlesAReceiptThroughItsAllowedLifecycle() {
        TwoParticipantReceipt split = twoParticipantReceipt("lifecycle", new BigDecimal("10.00"));
        receiptService.addItemAllocation(split.receiptId(), split.itemId(), split.ownerId(),
                split.ownerParticipantId(), AllocationType.EXACT, new BigDecimal("6.00"));
        receiptService.addItemAllocation(split.receiptId(), split.itemId(), split.ownerId(),
                split.friendParticipantId(), AllocationType.EXACT, new BigDecimal("4.00"));

        Receipt finalized = receiptService.finalizeReceipt(split.receiptId(), split.ownerId());
        assertThat(finalized.getStatus()).isEqualTo(ReceiptStatus.FINALIZED);
        assertThatThrownBy(() -> receiptService.calculateSplit(split.receiptId(), split.ownerId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only draft receipts can be modified");

        Receipt reopened = receiptService.reopenReceipt(split.receiptId(), split.ownerId());
        assertThat(reopened.getStatus()).isEqualTo(ReceiptStatus.DRAFT);

        Receipt finalizedAgain = receiptService.finalizeReceipt(split.receiptId(), split.ownerId());
        assertThat(finalizedAgain.getStatus()).isEqualTo(ReceiptStatus.FINALIZED);
        Receipt settled = receiptService.settleReceipt(split.receiptId(), split.ownerId());
        assertThat(settled.getStatus()).isEqualTo(ReceiptStatus.SETTLED);
        assertThatThrownBy(() -> receiptService.reopenReceipt(split.receiptId(), split.ownerId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only finalized receipts can be reopened");
    }

    @Test
    void returnsAReceiptWithParticipantsAllocationsAndBalances() {
        TwoParticipantReceipt split = twoParticipantReceipt("detailed-read", new BigDecimal("10.00"));
        receiptService.addItemAllocation(split.receiptId(), split.itemId(), split.ownerId(),
                split.ownerParticipantId(), AllocationType.EXACT, new BigDecimal("6.00"));
        receiptService.addItemAllocation(split.receiptId(), split.itemId(), split.ownerId(),
                split.friendParticipantId(), AllocationType.EXACT, new BigDecimal("4.00"));
        receiptService.calculateSplit(split.receiptId(), split.ownerId());

        ReceiptResponse receipt = receiptService.getReceipt(split.receiptId(), split.ownerId());

        assertThat(receipt.id()).isEqualTo(split.receiptId());
        assertThat(receipt.participants()).hasSize(2);
        assertThat(receipt.participants()).extracting(participant -> participant.finalOwedAmount())
                .containsExactlyInAnyOrder(new BigDecimal("6.00"), new BigDecimal("4.00"));
        assertThat(receipt.items()).singleElement().satisfies(item ->
                assertThat(item.allocations()).hasSize(2));
        assertThat(receiptService.listReceipts(split.ownerId()))
                .extracting(ReceiptResponse::id)
                .contains(split.receiptId());
    }

    @Test
    void updatesAnItemAndRecalculatesTheReceiptTotal() {
        TwoParticipantReceipt split = twoParticipantReceipt("item-edit", new BigDecimal("10.00"));

        ReceiptItem updated = receiptService.updateReceiptItem(split.receiptId(), split.itemId(), split.ownerId(),
                "Updated item", new BigDecimal("2"), new BigDecimal("7.50"));
        ReceiptResponse receipt = receiptService.getReceipt(split.receiptId(), split.ownerId());

        assertThat(updated.getTotal()).isEqualByComparingTo("15.00");
        assertThat(receipt.subtotal()).isEqualByComparingTo("15.00");
        assertThat(receipt.total()).isEqualByComparingTo("15.00");
    }

    @Test
    void updatesAndDeletesAllocationsBeforeRemovingAParticipant() {
        TwoParticipantReceipt split = twoParticipantReceipt("allocation-edit", new BigDecimal("10.00"));
        var ownerAllocation = receiptService.addItemAllocation(split.receiptId(), split.itemId(), split.ownerId(),
                split.ownerParticipantId(), AllocationType.EXACT, new BigDecimal("6.00"));
        var friendAllocation = receiptService.addItemAllocation(split.receiptId(), split.itemId(), split.ownerId(),
                split.friendParticipantId(), AllocationType.EXACT, new BigDecimal("4.00"));

        receiptService.updateItemAllocation(split.receiptId(), split.itemId(), ownerAllocation.getId(), split.ownerId(),
                AllocationType.EXACT, new BigDecimal("5.00"));
        receiptService.updateItemAllocation(split.receiptId(), split.itemId(), friendAllocation.getId(), split.ownerId(),
                AllocationType.EXACT, new BigDecimal("5.00"));
        assertThat(receiptService.calculateSplit(split.receiptId(), split.ownerId()).getItems().getFirst().getAllocations())
                .extracting(allocation -> allocation.getFinalAmount())
                .containsExactlyInAnyOrder(new BigDecimal("5.00"), new BigDecimal("5.00"));

        assertThatThrownBy(() -> receiptService.removeParticipant(split.receiptId(), split.friendParticipantId(), split.ownerId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Remove the participant's allocations");
        receiptService.deleteItemAllocation(split.receiptId(), split.itemId(), friendAllocation.getId(), split.ownerId());
        receiptService.removeParticipant(split.receiptId(), split.friendParticipantId(), split.ownerId());

        assertThat(receiptService.getReceipt(split.receiptId(), split.ownerId()).participants()).hasSize(1);
    }

    @Test
    void deletesAnItemAndRecalculatesTheReceiptTotal() {
        User owner = userRepository.save(user("owner-item-delete", "owner-item-delete@example.com"));
        Receipt receipt = receiptService.createReceipt(owner.getId(), receiptWithTwoItems());

        receiptService.deleteReceiptItem(receipt.getId(), receipt.getItems().getFirst().getId(), owner.getId());
        ReceiptResponse updated = receiptService.getReceipt(receipt.getId(), owner.getId());

        assertThat(updated.items()).singleElement().satisfies(item ->
                assertThat(item.total()).isEqualByComparingTo("10.00"));
        assertThat(updated.subtotal()).isEqualByComparingTo("10.00");
        assertThat(updated.total()).isEqualByComparingTo("10.00");
    }

    @Test
    void addsAnItemAndUpdatesReceiptDetailsWithServerCalculatedTotals() {
        TwoParticipantReceipt split = twoParticipantReceipt("add-item-details", new BigDecimal("10.00"));

        ReceiptItem added = receiptService.addReceiptItem(split.receiptId(), split.ownerId(), "Dessert",
                new BigDecimal("2"), new BigDecimal("3.00"));
        Receipt updated = receiptService.updateReceiptDetails(split.receiptId(), split.ownerId(), "Cafe",
                LocalDateTime.of(2026, 9, 29, 18, 0), new BigDecimal("1.00"),
                new BigDecimal("0.80"), new BigDecimal("0.20"), new BigDecimal("2.00"), "CAD");

        assertThat(added.getId()).isNotNull();
        assertThat(added.getTotal()).isEqualByComparingTo("6.00");
        assertThat(updated.getMerchantName()).isEqualTo("Cafe");
        assertThat(updated.getCurrency()).isEqualTo("CAD");
        assertThat(updated.getSubtotal()).isEqualByComparingTo("16.00");
        assertThat(updated.getTotal()).isEqualByComparingTo("18.00");
    }

    @Test
    void replacesAnEntireDraftInOneRequest() {
        TwoParticipantReceipt split = twoParticipantReceipt("bulk-draft", new BigDecimal("10.00"));
        SaveReceiptDraftRequest request = new SaveReceiptDraftRequest();
        request.setMerchantName("Bulk Bistro");
        request.setPurchaseDate(LocalDateTime.of(2026, 9, 30, 18, 0));
        request.setDiscount(BigDecimal.ZERO);
        request.setTax(new BigDecimal("1.00"));
        request.setFee(BigDecimal.ZERO);
        request.setTip(BigDecimal.ZERO);
        request.setCurrency("USD");
        request.setParticipantUserIds(List.of(split.friendUserId()));
        request.setItems(List.of(
                draftItem("Pizza", "10.00", allocation(split.ownerId(), "EXACT", "6.00"),
                        allocation(split.friendUserId(), "EXACT", "4.00")),
                draftItem("Salad", "5.00", allocation(split.friendUserId(), "EXACT", "5.00"))));

        Receipt saved = receiptService.saveDraft(split.receiptId(), split.ownerId(), request);
        Receipt calculated = receiptService.calculateSplit(saved.getId(), split.ownerId());

        assertThat(saved.getMerchantName()).isEqualTo("Bulk Bistro");
        assertThat(saved.getItems()).hasSize(2);
        assertThat(saved.getParticipants()).hasSize(2);
        assertThat(calculated.getTotal()).isEqualByComparingTo("16.00");
        assertThat(calculated.getParticipants()).extracting(participant -> participant.getFinalOwedAmount())
                .containsExactlyInAnyOrder(new BigDecimal("6.40"), new BigDecimal("9.60"));
    }

    @Test
    void usesManualTaxFeeAndTipAllocationsWhenTheyAreProvided() {
        TwoParticipantReceipt split = twoParticipantReceipt("manual-adjustments", new BigDecimal("10.00"));
        receiptService.updateReceiptDetails(split.receiptId(), split.ownerId(), "Cafe", null, BigDecimal.ZERO,
                new BigDecimal("2.00"), new BigDecimal("1.00"), new BigDecimal("3.00"), "USD");
        addExactSplit(split);

        receiptService.saveAdjustmentAllocations(split.receiptId(), split.ownerId(), List.of(
                adjustment("TAX", split.ownerId(), "2.00"),
                adjustment("FEE", split.friendUserId(), "1.00"),
                adjustment("TIP", split.friendUserId(), "3.00")));

        Receipt calculated = receiptService.calculateSplit(split.receiptId(), split.ownerId());

        assertThat(calculated.getParticipants()).extracting(participant -> participant.getFinalOwedAmount())
                .containsExactlyInAnyOrder(new BigDecimal("8.00"), new BigDecimal("8.00"));
        assertThat(receiptService.getReceipt(split.receiptId(), split.ownerId()).adjustmentAllocations())
                .hasSize(3);
    }

    @Test
    void rejectsManualAdjustmentAllocationsThatDoNotEqualTheCharge() {
        TwoParticipantReceipt split = twoParticipantReceipt("invalid-manual-adjustments", new BigDecimal("10.00"));
        receiptService.updateReceiptDetails(split.receiptId(), split.ownerId(), "Cafe", null, BigDecimal.ZERO,
                new BigDecimal("2.00"), BigDecimal.ZERO, BigDecimal.ZERO, "USD");
        addExactSplit(split);
        receiptService.saveAdjustmentAllocations(split.receiptId(), split.ownerId(),
                List.of(adjustment("TAX", split.ownerId(), "1.50")));

        assertThatThrownBy(() -> receiptService.calculateSplit(split.receiptId(), split.ownerId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Manual tax allocations must equal 2.00");
    }

    @Test
    void deletesDraftAndFinalizedReceiptsButRejectsSettledReceipts() {
        TwoParticipantReceipt draft = twoParticipantReceipt("delete-draft", new BigDecimal("10.00"));
        receiptService.deleteReceipt(draft.receiptId(), draft.ownerId());
        assertThatThrownBy(() -> receiptService.getReceipt(draft.receiptId(), draft.ownerId()))
                .isInstanceOf(ResourceNotFoundException.class);

        TwoParticipantReceipt finalized = twoParticipantReceipt("delete-finalized", new BigDecimal("10.00"));
        addExactSplit(finalized);
        receiptService.finalizeReceipt(finalized.receiptId(), finalized.ownerId());
        receiptService.deleteReceipt(finalized.receiptId(), finalized.ownerId());
        assertThatThrownBy(() -> receiptService.getReceipt(finalized.receiptId(), finalized.ownerId()))
                .isInstanceOf(ResourceNotFoundException.class);

        TwoParticipantReceipt settled = twoParticipantReceipt("delete-settled", new BigDecimal("10.00"));
        addExactSplit(settled);
        receiptService.finalizeReceipt(settled.receiptId(), settled.ownerId());
        receiptService.settleReceipt(settled.receiptId(), settled.ownerId());
        assertThatThrownBy(() -> receiptService.deleteReceipt(settled.receiptId(), settled.ownerId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Settled receipts cannot be deleted");
    }

    @Test
    void participantsOnlySeeFinalizedAndSettledReceiptsWithoutDuplicates() {
        TwoParticipantReceipt split = twoParticipantReceipt("view-access", new BigDecimal("10.00"));
        Receipt ownReceipt = receiptService.createReceipt(split.friendUserId(), receiptWithOneItem(BigDecimal.ONE));
        User outsider = userRepository.save(user("view-outsider", "view-outsider@example.com"));
        receiptService.createReceipt(outsider.getId(), receiptWithOneItem(BigDecimal.ONE));
        addExactSplit(split);

        assertThatThrownBy(() -> receiptService.getReceipt(split.receiptId(), split.friendUserId()))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThat(receiptService.listReceipts(split.friendUserId())).extracting(ReceiptResponse::id)
                .containsExactly(ownReceipt.getId());
        assertThat(receiptService.getReceipt(split.receiptId(), split.ownerId()).status()).isEqualTo("DRAFT");
        assertThat(receiptService.listReceipts(split.ownerId())).extracting(ReceiptResponse::id)
                .containsExactly(split.receiptId());

        for (ReceiptStatus status : List.of(ReceiptStatus.FINALIZED, ReceiptStatus.SETTLED)) {
            if (status == ReceiptStatus.FINALIZED) {
                receiptService.finalizeReceipt(split.receiptId(), split.ownerId());
            } else if (status == ReceiptStatus.SETTLED) {
                receiptService.settleReceipt(split.receiptId(), split.ownerId());
            }
            ReceiptResponse viewed = receiptService.getReceipt(split.receiptId(), split.friendUserId());
            assertThat(viewed.status()).isEqualTo(status.name());
            assertThat(viewed.items()).hasSize(1);
            assertThat(viewed.participants()).hasSize(2);
            assertThat(receiptService.listReceipts(split.friendUserId())).extracting(ReceiptResponse::id)
                    .containsExactlyInAnyOrder(split.receiptId(), ownReceipt.getId());
            assertThat(receiptService.listReceipts(split.ownerId())).extracting(ReceiptResponse::id)
                    .containsExactly(split.receiptId());
            assertThatThrownBy(() -> receiptService.getReceipt(split.receiptId(), outsider.getId()))
                    .isInstanceOf(ForbiddenOperationException.class);
        }
    }

    @Test
    void removingParticipantRevokesViewAndListAccess() {
        TwoParticipantReceipt split = twoParticipantReceipt("revoked-access", new BigDecimal("10.00"));
        receiptService.addItemAllocation(split.receiptId(), split.itemId(), split.ownerId(),
                split.ownerParticipantId(), AllocationType.EXACT, new BigDecimal("10.00"));
        receiptService.finalizeReceipt(split.receiptId(), split.ownerId());
        assertThat(receiptService.getReceipt(split.receiptId(), split.friendUserId()).id())
                .isEqualTo(split.receiptId());
        receiptService.reopenReceipt(split.receiptId(), split.ownerId());
        receiptService.removeParticipant(split.receiptId(), split.friendParticipantId(), split.ownerId());
        receiptService.finalizeReceipt(split.receiptId(), split.ownerId());
        assertThatThrownBy(() -> receiptService.getReceipt(split.receiptId(), split.friendUserId()))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThat(receiptService.listReceipts(split.friendUserId())).isEmpty();
    }

    @Test
    void reopeningHidesReceiptFromParticipantUntilFinalizedAgain() {
        TwoParticipantReceipt split = twoParticipantReceipt("draft-privacy", new BigDecimal("10.00"));
        addExactSplit(split);
        receiptService.finalizeReceipt(split.receiptId(), split.ownerId());
        assertThat(receiptService.getReceipt(split.receiptId(), split.friendUserId()).status()).isEqualTo("FINALIZED");
        receiptService.reopenReceipt(split.receiptId(), split.ownerId());
        assertThatThrownBy(() -> receiptService.getReceipt(split.receiptId(), split.friendUserId()))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThat(receiptService.listReceipts(split.friendUserId())).isEmpty();
        assertThat(receiptService.getReceipt(split.receiptId(), split.ownerId()).status()).isEqualTo("DRAFT");
        receiptService.finalizeReceipt(split.receiptId(), split.ownerId());
        assertThat(receiptService.getReceipt(split.receiptId(), split.friendUserId()).status()).isEqualTo("FINALIZED");
        assertThat(receiptService.listReceipts(split.friendUserId())).extracting(ReceiptResponse::id)
                .containsExactly(split.receiptId());
    }

    @Test
    void participantsCannotModifyReceipts() {
        TwoParticipantReceipt split = twoParticipantReceipt("read-only", new BigDecimal("10.00"));
        Long id = split.receiptId();
        Long viewer = split.friendUserId();
        Long item = split.itemId();
        var allocation = receiptService.addItemAllocation(id, item, split.ownerId(),
                split.ownerParticipantId(), AllocationType.EXACT, new BigDecimal("10.00"));
        List<Runnable> edits = List.of(
                () -> receiptService.updateReceiptDetails(id, viewer, "Changed", null,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "USD"),
                () -> receiptService.saveDraft(id, viewer, new SaveReceiptDraftRequest()),
                () -> receiptService.saveAdjustmentAllocations(id, viewer, List.of()),
                () -> receiptService.addParticipant(id, viewer, viewer),
                () -> receiptService.removeParticipant(id, split.friendParticipantId(), viewer),
                () -> receiptService.addReceiptItem(id, viewer, "New", BigDecimal.ONE, BigDecimal.ONE),
                () -> receiptService.updateReceiptItem(id, item, viewer, "Changed", BigDecimal.ONE, BigDecimal.ONE),
                () -> receiptService.deleteReceiptItem(id, item, viewer),
                () -> receiptService.addItemAllocation(id, item, viewer, split.friendParticipantId(),
                        AllocationType.EQUAL, BigDecimal.ONE),
                () -> receiptService.updateItemAllocation(id, item, allocation.getId(), viewer,
                        AllocationType.EXACT, BigDecimal.ONE),
                () -> receiptService.deleteItemAllocation(id, item, allocation.getId(), viewer),
                () -> receiptService.calculateSplit(id, viewer),
                () -> receiptService.finalizeReceipt(id, viewer),
                () -> receiptService.reopenReceipt(id, viewer),
                () -> receiptService.settleReceipt(id, viewer),
                () -> receiptService.deleteReceipt(id, viewer));
        for (Runnable edit : edits) {
            assertThatThrownBy(edit::run).isInstanceOf(ForbiddenOperationException.class);
        }
        receiptService.finalizeReceipt(id, split.ownerId());
        assertThatThrownBy(() -> receiptService.reopenReceipt(id, viewer))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> receiptService.settleReceipt(id, viewer))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThat(receiptService.getReceipt(id, viewer).status()).isEqualTo("FINALIZED");
    }

    private TwoParticipantReceipt twoParticipantReceipt(String suffix, BigDecimal itemTotal) {
        User owner = userRepository.save(user("owner-" + suffix, "owner-" + suffix + "@example.com"));
        User friend = userRepository.save(user("friend-" + suffix, "friend-" + suffix + "@example.com"));
        Receipt receipt = receiptService.createReceipt(owner.getId(), receiptWithOneItem(itemTotal));
        var friendParticipant = receiptService.addParticipant(receipt.getId(), owner.getId(), friend.getId());
        return new TwoParticipantReceipt(receipt.getId(), receipt.getItems().getFirst().getId(), owner.getId(),
                receipt.getParticipants().getFirst().getId(), friendParticipant.getId(), friend.getId());
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

    private SaveReceiptDraftItemRequest draftItem(String name, String unitPrice,
                                                   SaveReceiptDraftAllocationRequest... allocations) {
        SaveReceiptDraftItemRequest item = new SaveReceiptDraftItemRequest();
        item.setName(name);
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(new BigDecimal(unitPrice));
        item.setAllocations(List.of(allocations));
        return item;
    }

    private SaveReceiptDraftAllocationRequest allocation(Long userId, String type, String inputValue) {
        SaveReceiptDraftAllocationRequest allocation = new SaveReceiptDraftAllocationRequest();
        allocation.setUserId(userId);
        allocation.setAllocationType(AllocationType.valueOf(type));
        allocation.setInputValue(new BigDecimal(inputValue));
        return allocation;
    }

    private SaveReceiptDraftAdjustmentAllocationRequest adjustment(String type, Long userId, String amount) {
        SaveReceiptDraftAdjustmentAllocationRequest allocation = new SaveReceiptDraftAdjustmentAllocationRequest();
        allocation.setAdjustmentType(ReceiptAdjustmentType.valueOf(type));
        allocation.setUserId(userId);
        allocation.setAmount(new BigDecimal(amount));
        return allocation;
    }

    private void addExactSplit(TwoParticipantReceipt split) {
        receiptService.addItemAllocation(split.receiptId(), split.itemId(), split.ownerId(),
                split.ownerParticipantId(), AllocationType.EXACT, new BigDecimal("6.00"));
        receiptService.addItemAllocation(split.receiptId(), split.itemId(), split.ownerId(),
                split.friendParticipantId(), AllocationType.EXACT, new BigDecimal("4.00"));
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

    private Receipt receiptWithTwoItems() {
        Receipt receipt = receiptWithOneItem(new BigDecimal("20.00"));
        ReceiptItem firstItem = receipt.getItems().getFirst();
        firstItem.setName("First item");
        firstItem.setUnitPrice(new BigDecimal("10.00"));
        firstItem.setTotal(new BigDecimal("10.00"));

        ReceiptItem secondItem = new ReceiptItem();
        secondItem.setName("Second item");
        secondItem.setQuantity(BigDecimal.ONE);
        secondItem.setUnitPrice(new BigDecimal("10.00"));
        secondItem.setTotal(new BigDecimal("10.00"));
        receipt.getItems().add(secondItem);
        return receipt;
    }

    private record TwoParticipantReceipt(Long receiptId, Long itemId, Long ownerId,
                                         Long ownerParticipantId, Long friendParticipantId,
                                         Long friendUserId) { }
}
