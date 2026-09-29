package com.cs.receipt.service;

import com.cs.receipt.model.Receipt;
import com.cs.receipt.model.ReceiptItem;
import com.cs.receipt.model.ReceiptParticipant;
import com.cs.receipt.model.ReceiptItemAllocation;
import com.cs.receipt.model.AllocationType;
import com.cs.receipt.model.User;
import com.cs.receipt.repository.ReceiptRepository;
import com.cs.receipt.repository.ReceiptParticipantRepository;
import com.cs.receipt.repository.ReceiptItemAllocationRepository;
import com.cs.receipt.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

@Service
public class ReceiptService {

    private final ReceiptRepository receiptRepository;
    private final UserRepository userRepository;
    private final ReceiptParticipantRepository receiptParticipantRepository;
    private final ReceiptItemAllocationRepository receiptItemAllocationRepository;

    public ReceiptService(
            ReceiptRepository receiptRepository,
            UserRepository userRepository,
            ReceiptParticipantRepository receiptParticipantRepository,
            ReceiptItemAllocationRepository receiptItemAllocationRepository) {

        this.receiptRepository = receiptRepository;
        this.userRepository = userRepository;
        this.receiptParticipantRepository = receiptParticipantRepository;
        this.receiptItemAllocationRepository = receiptItemAllocationRepository;
    }

    public Receipt createReceipt(Long userId, Receipt receipt) {

        User owner = userRepository.findById(userId)
                .orElseThrow(() ->
                        new IllegalArgumentException("User not found"));

        receipt.setOwner(owner);

        ReceiptParticipant ownerParticipant = new ReceiptParticipant();
        ownerParticipant.setReceipt(receipt);
        ownerParticipant.setUser(owner);
        receipt.getParticipants().add(ownerParticipant);

        BigDecimal calculatedSubtotal = BigDecimal.ZERO;

        for (ReceiptItem item : receipt.getItems()) {

            BigDecimal calculatedItemTotal = item.getQuantity()
                    .multiply(item.getUnitPrice())
                    .setScale(2, RoundingMode.HALF_UP);

            BigDecimal providedItemTotal = item.getTotal()
                    .setScale(2, RoundingMode.HALF_UP);

            BigDecimal difference = calculatedItemTotal
                    .subtract(providedItemTotal)
                    .abs();

            if (difference.compareTo(new BigDecimal("0.01")) > 0) {
                throw new IllegalArgumentException(
                        "Item total does not match quantity × unit price for: "
                                + item.getName()
                );
            }

            item.setTotal(calculatedItemTotal);
            item.setReceipt(receipt);

            calculatedSubtotal = calculatedSubtotal
                    .add(calculatedItemTotal);
        }

        calculatedSubtotal = calculatedSubtotal
                .setScale(2, RoundingMode.HALF_UP);

        receipt.setSubtotal(calculatedSubtotal);

        BigDecimal discount = receipt.getDiscount() != null
                ? receipt.getDiscount()
                : BigDecimal.ZERO;

        BigDecimal tax = receipt.getTax() != null
                ? receipt.getTax()
                : BigDecimal.ZERO;

        BigDecimal fee = receipt.getFee() != null
                ? receipt.getFee()
                : BigDecimal.ZERO;

        BigDecimal tip = receipt.getTip() != null
                ? receipt.getTip()
                : BigDecimal.ZERO;

        BigDecimal calculatedTotal = calculatedSubtotal
                .subtract(discount)
                .add(tax)
                .add(fee)
                .add(tip)
                .setScale(2, RoundingMode.HALF_UP);

        receipt.setDiscount(discount.setScale(2, RoundingMode.HALF_UP));
        receipt.setTax(tax.setScale(2, RoundingMode.HALF_UP));
        receipt.setFee(fee.setScale(2, RoundingMode.HALF_UP));
        receipt.setTip(tip.setScale(2, RoundingMode.HALF_UP));

        BigDecimal providedTotal = receipt.getTotal()
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal totalDifference = calculatedTotal
                .subtract(providedTotal)
                .abs();

        if (totalDifference.compareTo(new BigDecimal("0.01")) > 0) {
            throw new IllegalArgumentException(
                    "Receipt total does not match subtotal - discount + tax + fee + tip"
            );
        }

        receipt.setTotal(calculatedTotal);

        return receiptRepository.save(receipt);
    }

    @Transactional
    public ReceiptParticipant addParticipant(Long receiptId, Long ownerId, Long participantUserId) {
        Receipt receipt = findOwnedDraft(receiptId, ownerId);
        User participantUser = userRepository.findById(participantUserId)
                .orElseThrow(() -> new IllegalArgumentException("Participant user not found"));

        boolean alreadyAdded = receipt.getParticipants().stream()
                .anyMatch(participant -> participant.getUser().getId().equals(participantUserId));
        if (alreadyAdded) {
            throw new IllegalArgumentException("User is already a receipt participant");
        }

        ReceiptParticipant participant = new ReceiptParticipant();
        participant.setReceipt(receipt);
        participant.setUser(participantUser);
        receipt.getParticipants().add(participant);
        return receiptParticipantRepository.saveAndFlush(participant);
    }

    @Transactional
    public ReceiptItemAllocation addItemAllocation(Long receiptId, Long itemId, Long ownerId,
                                                    Long participantId, AllocationType type,
                                                    BigDecimal inputValue) {
        Receipt receipt = findOwnedDraft(receiptId, ownerId);
        ReceiptItem item = receipt.getItems().stream()
                .filter(candidate -> candidate.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Receipt item not found"));
        ReceiptParticipant participant = receipt.getParticipants().stream()
                .filter(candidate -> candidate.getId().equals(participantId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Receipt participant not found"));

        boolean alreadyAllocated = item.getAllocations().stream()
                .anyMatch(allocation -> allocation.getParticipant().getId().equals(participantId));
        if (alreadyAllocated) {
            throw new IllegalArgumentException("Participant already has an allocation for this item");
        }

        boolean usesAnotherAllocationType = item.getAllocations().stream()
                .map(ReceiptItemAllocation::getAllocationType)
                .anyMatch(existingType -> existingType != type);
        if (usesAnotherAllocationType) {
            throw new IllegalArgumentException("All allocations for an item must use the same allocation type");
        }

        BigDecimal allocatedInputTotal = item.getAllocations().stream()
                .map(ReceiptItemAllocation::getInputValue)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(inputValue);

        if (type == AllocationType.EXACT && allocatedInputTotal.compareTo(item.getTotal()) > 0) {
            throw new IllegalArgumentException("Exact allocations cannot exceed the item total");
        }
        if (type == AllocationType.PERCENTAGE
                && allocatedInputTotal.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("Percentage allocations cannot exceed 100");
        }

        ReceiptItemAllocation allocation = new ReceiptItemAllocation();
        allocation.setReceiptItem(item);
        allocation.setParticipant(participant);
        allocation.setAllocationType(type);
        allocation.setInputValue(inputValue);
        item.getAllocations().add(allocation);
        return receiptItemAllocationRepository.saveAndFlush(allocation);
    }

    private Receipt findOwnedDraft(Long receiptId, Long ownerId) {
        Receipt receipt = receiptRepository.findById(receiptId)
                .orElseThrow(() -> new IllegalArgumentException("Receipt not found"));
        if (!receipt.getOwner().getId().equals(ownerId)) {
            throw new IllegalArgumentException("Only the receipt owner can modify it");
        }
        if (receipt.getStatus() != com.cs.receipt.model.ReceiptStatus.DRAFT) {
            throw new IllegalArgumentException("Only draft receipts can be modified");
        }
        return receipt;
    }
}
