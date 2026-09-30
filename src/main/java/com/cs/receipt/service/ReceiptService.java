package com.cs.receipt.service;

import com.cs.receipt.model.Receipt;
import com.cs.receipt.model.ReceiptItem;
import com.cs.receipt.model.ReceiptParticipant;
import com.cs.receipt.model.ReceiptItemAllocation;
import com.cs.receipt.model.AllocationType;
import com.cs.receipt.model.ReceiptStatus;
import com.cs.receipt.model.User;
import com.cs.receipt.dto.ReceiptResponse;
import com.cs.receipt.repository.ReceiptRepository;
import com.cs.receipt.repository.ReceiptParticipantRepository;
import com.cs.receipt.repository.ReceiptItemAllocationRepository;
import com.cs.receipt.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    @Transactional(readOnly = true)
    public ReceiptResponse getReceipt(Long receiptId, Long userId) {
        return ReceiptResponse.fromReceipt(findOwned(receiptId, userId));
    }

    @Transactional(readOnly = true)
    public List<ReceiptResponse> listReceipts(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new IllegalArgumentException("User not found");
        }
        return receiptRepository.findByOwnerId(userId).stream()
                .map(ReceiptResponse::fromReceipt)
                .toList();
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

    /**
     * Calculates and persists the current draft split without changing its status.
     * Every item must have a complete set of allocations using one allocation type.
     */
    @Transactional
    public Receipt calculateSplit(Long receiptId, Long ownerId) {
        Receipt receipt = findOwnedDraft(receiptId, ownerId);
        Map<ReceiptParticipant, BigDecimal> itemSubtotals = new HashMap<>();
        receipt.getParticipants().forEach(participant ->
                itemSubtotals.put(participant, BigDecimal.ZERO.setScale(2)));

        for (ReceiptItem item : receipt.getItems()) {
            calculateItemAllocations(item);
            for (ReceiptItemAllocation allocation : item.getAllocations()) {
                itemSubtotals.merge(allocation.getParticipant(), allocation.getFinalAmount(), BigDecimal::add);
            }
        }

        Map<ReceiptParticipant, BigDecimal> discounts = distributeAmount(receipt.getDiscount(), itemSubtotals);
        Map<ReceiptParticipant, BigDecimal> taxes = distributeAmount(receipt.getTax(), itemSubtotals);
        Map<ReceiptParticipant, BigDecimal> fees = distributeAmount(receipt.getFee(), itemSubtotals);
        Map<ReceiptParticipant, BigDecimal> tips = distributeAmount(receipt.getTip(), itemSubtotals);

        for (ReceiptParticipant participant : receipt.getParticipants()) {
            BigDecimal owed = itemSubtotals.get(participant)
                    .subtract(discounts.get(participant))
                    .add(taxes.get(participant))
                    .add(fees.get(participant))
                    .add(tips.get(participant))
                    .setScale(2, RoundingMode.HALF_UP);
            participant.setFinalOwedAmount(owed);
        }

        receiptItemAllocationRepository.saveAll(receipt.getItems().stream()
                .flatMap(item -> item.getAllocations().stream()).toList());
        receiptParticipantRepository.saveAll(receipt.getParticipants());
        return receiptRepository.saveAndFlush(receipt);
    }

    /** Calculates the draft one final time, then locks its split for review and payment. */
    @Transactional
    public Receipt finalizeReceipt(Long receiptId, Long ownerId) {
        Receipt receipt = calculateSplit(receiptId, ownerId);
        receipt.setStatus(ReceiptStatus.FINALIZED);
        return receiptRepository.saveAndFlush(receipt);
    }

    /** Reopens a finalized receipt so its items and allocations can be revised. */
    @Transactional
    public Receipt reopenReceipt(Long receiptId, Long ownerId) {
        Receipt receipt = findOwned(receiptId, ownerId);
        if (receipt.getStatus() != ReceiptStatus.FINALIZED) {
            throw new IllegalArgumentException("Only finalized receipts can be reopened");
        }
        receipt.setStatus(ReceiptStatus.DRAFT);
        return receiptRepository.saveAndFlush(receipt);
    }

    /** Marks a finalized receipt as paid. Settled receipts cannot be reopened or edited. */
    @Transactional
    public Receipt settleReceipt(Long receiptId, Long ownerId) {
        Receipt receipt = findOwned(receiptId, ownerId);
        if (receipt.getStatus() != ReceiptStatus.FINALIZED) {
            throw new IllegalArgumentException("Only finalized receipts can be settled");
        }
        receipt.setStatus(ReceiptStatus.SETTLED);
        return receiptRepository.saveAndFlush(receipt);
    }

    private void calculateItemAllocations(ReceiptItem item) {
        List<ReceiptItemAllocation> allocations = item.getAllocations();
        if (allocations.isEmpty()) {
            throw new IllegalArgumentException("Every item must have at least one allocation: " + item.getName());
        }

        AllocationType type = allocations.getFirst().getAllocationType();
        if (allocations.stream().anyMatch(allocation -> allocation.getAllocationType() != type)) {
            throw new IllegalArgumentException("All allocations for an item must use the same allocation type");
        }

        List<BigDecimal> weights = switch (type) {
            case EXACT -> exactWeights(item, allocations);
            case EQUAL -> allocations.stream().map(allocation -> BigDecimal.ONE).toList();
            case PERCENTAGE -> percentageWeights(allocations);
            case SHARES -> shareWeights(allocations);
        };
        List<BigDecimal> amounts = distributeByWeights(item.getTotal(), allocations, weights);
        for (int index = 0; index < allocations.size(); index++) {
            allocations.get(index).setFinalAmount(amounts.get(index));
        }
    }

    private List<BigDecimal> exactWeights(ReceiptItem item, List<ReceiptItemAllocation> allocations) {
        BigDecimal total = allocations.stream().map(ReceiptItemAllocation::getInputValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(item.getTotal()) != 0) {
            throw new IllegalArgumentException("Exact allocations must equal the item total: " + item.getName());
        }
        return allocations.stream().map(ReceiptItemAllocation::getInputValue).toList();
    }

    private List<BigDecimal> percentageWeights(List<ReceiptItemAllocation> allocations) {
        BigDecimal total = allocations.stream().map(ReceiptItemAllocation::getInputValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(new BigDecimal("100")) != 0) {
            throw new IllegalArgumentException("Percentage allocations must equal 100");
        }
        return allocations.stream().map(ReceiptItemAllocation::getInputValue).toList();
    }

    private List<BigDecimal> shareWeights(List<ReceiptItemAllocation> allocations) {
        BigDecimal total = allocations.stream().map(ReceiptItemAllocation::getInputValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) {
            throw new IllegalArgumentException("Share allocations must have a positive total");
        }
        return allocations.stream().map(ReceiptItemAllocation::getInputValue).toList();
    }

    private Map<ReceiptParticipant, BigDecimal> distributeAmount(
            BigDecimal amount, Map<ReceiptParticipant, BigDecimal> weights) {
        List<ReceiptParticipant> participants = new ArrayList<>(weights.keySet());
        participants.sort(Comparator.comparing(ReceiptParticipant::getId));
        List<BigDecimal> values = distributeByWeights(amount, participants,
                participants.stream().map(weights::get).toList());
        Map<ReceiptParticipant, BigDecimal> result = new HashMap<>();
        for (int index = 0; index < participants.size(); index++) {
            result.put(participants.get(index), values.get(index));
        }
        return result;
    }

    private <T> List<BigDecimal> distributeByWeights(
            BigDecimal total, List<T> recipients, List<BigDecimal> weights) {
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("Cannot distribute an amount without participants");
        }
        BigDecimal weightTotal = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (weightTotal.signum() == 0) {
            weights = recipients.stream().map(recipient -> BigDecimal.ONE).toList();
            weightTotal = BigDecimal.valueOf(recipients.size());
        }
        BigDecimal finalWeightTotal = weightTotal;

        List<BigDecimal> unrounded = weights.stream()
                .map(weight -> total.multiply(weight).divide(finalWeightTotal, 12, RoundingMode.HALF_UP))
                .toList();
        List<BigDecimal> rounded = new ArrayList<>(unrounded.stream()
                .map(value -> value.setScale(2, RoundingMode.DOWN)).toList());
        BigDecimal remainder = total.subtract(rounded.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        int centsToAssign = remainder.movePointRight(2).intValueExact();
        if (centsToAssign < 0) {
            throw new IllegalStateException("Unexpected negative rounding remainder");
        }

        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < recipients.size(); index++) {
            order.add(index);
        }
        order.sort(Comparator.<Integer, BigDecimal>comparing(index ->
                        unrounded.get(index).subtract(rounded.get(index)))
                .reversed().thenComparingInt(index -> index));
        for (int index = 0; index < centsToAssign; index++) {
            int recipientIndex = order.get(index % order.size());
            rounded.set(recipientIndex, rounded.get(recipientIndex).add(new BigDecimal("0.01")));
        }
        return rounded;
    }

    private Receipt findOwnedDraft(Long receiptId, Long ownerId) {
        Receipt receipt = findOwned(receiptId, ownerId);
        if (receipt.getStatus() != ReceiptStatus.DRAFT) {
            throw new IllegalArgumentException("Only draft receipts can be modified");
        }
        return receipt;
    }

    private Receipt findOwned(Long receiptId, Long ownerId) {
        Receipt receipt = receiptRepository.findById(receiptId)
                .orElseThrow(() -> new IllegalArgumentException("Receipt not found"));
        if (!receipt.getOwner().getId().equals(ownerId)) {
            throw new IllegalArgumentException("Only the receipt owner can modify it");
        }
        return receipt;
    }
}
