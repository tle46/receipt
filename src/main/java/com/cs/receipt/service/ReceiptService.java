package com.cs.receipt.service;

import com.cs.receipt.model.Receipt;
import com.cs.receipt.model.ReceiptItem;
import com.cs.receipt.model.ReceiptParticipant;
import com.cs.receipt.model.ReceiptItemAllocation;
import com.cs.receipt.model.AllocationType;
import com.cs.receipt.model.ReceiptStatus;
import com.cs.receipt.model.User;
import com.cs.receipt.dto.ReceiptResponse;
import com.cs.receipt.dto.SaveReceiptDraftAllocationRequest;
import com.cs.receipt.dto.SaveReceiptDraftItemRequest;
import com.cs.receipt.dto.SaveReceiptDraftRequest;
import com.cs.receipt.exception.ForbiddenOperationException;
import com.cs.receipt.exception.ResourceNotFoundException;
import com.cs.receipt.repository.ReceiptRepository;
import com.cs.receipt.repository.ReceiptParticipantRepository;
import com.cs.receipt.repository.ReceiptItemAllocationRepository;
import com.cs.receipt.repository.ReceiptItemRepository;
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
import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class ReceiptService {

    private final ReceiptRepository receiptRepository;
    private final UserRepository userRepository;
    private final ReceiptParticipantRepository receiptParticipantRepository;
    private final ReceiptItemAllocationRepository receiptItemAllocationRepository;
    private final ReceiptItemRepository receiptItemRepository;

    public ReceiptService(
            ReceiptRepository receiptRepository,
            UserRepository userRepository,
            ReceiptParticipantRepository receiptParticipantRepository,
            ReceiptItemAllocationRepository receiptItemAllocationRepository,
            ReceiptItemRepository receiptItemRepository) {

        this.receiptRepository = receiptRepository;
        this.userRepository = userRepository;
        this.receiptParticipantRepository = receiptParticipantRepository;
        this.receiptItemAllocationRepository = receiptItemAllocationRepository;
        this.receiptItemRepository = receiptItemRepository;
    }

    public Receipt createReceipt(Long userId, Receipt receipt) {

        User owner = userRepository.findById(userId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("User not found"));

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
            throw new ResourceNotFoundException("User not found");
        }
        return receiptRepository.findByOwnerId(userId).stream()
                .map(ReceiptResponse::fromReceipt)
                .toList();
    }

    @Transactional
    public ReceiptParticipant addParticipant(Long receiptId, Long ownerId, Long participantUserId) {
        Receipt receipt = findOwnedDraft(receiptId, ownerId);
        User participantUser = userRepository.findById(participantUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Participant user not found"));

        boolean alreadyAdded = receipt.getParticipants().stream()
                .anyMatch(participant -> participant.getUser().getId().equals(participantUserId));
        if (alreadyAdded) {
            throw new IllegalArgumentException("User is already a receipt participant");
        }

        ReceiptParticipant participant = new ReceiptParticipant();
        participant.setReceipt(receipt);
        participant.setUser(participantUser);
        receipt.getParticipants().add(participant);
        clearCalculatedAmounts(receipt);
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
                .orElseThrow(() -> new ResourceNotFoundException("Receipt item not found"));
        ReceiptParticipant participant = receipt.getParticipants().stream()
                .filter(candidate -> candidate.getId().equals(participantId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Receipt participant not found"));

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
        clearCalculatedAmounts(receipt);
        return receiptItemAllocationRepository.saveAndFlush(allocation);
    }

    @Transactional
    public ReceiptItem updateReceiptItem(Long receiptId, Long itemId, Long ownerId,
                                         String name, BigDecimal quantity, BigDecimal unitPrice) {
        Receipt receipt = findOwnedDraft(receiptId, ownerId);
        ReceiptItem item = findItem(receipt, itemId);
        item.setName(name);
        item.setQuantity(quantity);
        item.setUnitPrice(unitPrice);
        item.setTotal(quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP));
        recalculateReceiptTotal(receipt);
        clearCalculatedAmounts(receipt);
        receiptRepository.saveAndFlush(receipt);
        return item;
    }

    @Transactional
    public ReceiptItem addReceiptItem(Long receiptId, Long ownerId, String name,
                                      BigDecimal quantity, BigDecimal unitPrice) {
        Receipt receipt = findOwnedDraft(receiptId, ownerId);
        ReceiptItem item = new ReceiptItem();
        item.setReceipt(receipt);
        item.setName(name);
        item.setQuantity(quantity);
        item.setUnitPrice(unitPrice);
        item.setTotal(quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP));
        receipt.getItems().add(item);
        recalculateReceiptTotal(receipt);
        clearCalculatedAmounts(receipt);
        return receiptItemRepository.saveAndFlush(item);
    }

    @Transactional
    public Receipt updateReceiptDetails(Long receiptId, Long ownerId, String merchantName,
                                        java.time.LocalDateTime purchaseDate, BigDecimal discount,
                                        BigDecimal tax, BigDecimal fee, BigDecimal tip, String currency) {
        Receipt receipt = findOwnedDraft(receiptId, ownerId);
        receipt.setMerchantName(merchantName);
        receipt.setPurchaseDate(purchaseDate);
        receipt.setDiscount(discount.setScale(2, RoundingMode.HALF_UP));
        receipt.setTax(tax.setScale(2, RoundingMode.HALF_UP));
        receipt.setFee(fee.setScale(2, RoundingMode.HALF_UP));
        receipt.setTip(tip.setScale(2, RoundingMode.HALF_UP));
        receipt.setCurrency(currency);
        recalculateReceiptTotal(receipt);
        clearCalculatedAmounts(receipt);
        return receiptRepository.saveAndFlush(receipt);
    }

    /** Replaces every editable part of a draft receipt in one transaction. */
    @Transactional
    public Receipt saveDraft(Long receiptId, Long ownerId, SaveReceiptDraftRequest request) {
        Receipt receipt = findOwnedDraft(receiptId, ownerId);
        Set<Long> participantUserIds = new LinkedHashSet<>(request.getParticipantUserIds());
        if (participantUserIds.size() != request.getParticipantUserIds().size()) {
            throw new IllegalArgumentException("A participant can only appear once");
        }
        if (participantUserIds.contains(ownerId)) {
            throw new IllegalArgumentException("Do not include the receipt owner in participantUserIds");
        }

        Map<Long, User> usersById = new HashMap<>();
        usersById.put(ownerId, receipt.getOwner());
        for (Long participantUserId : participantUserIds) {
            User user = userRepository.findById(participantUserId)
                    .orElseThrow(() -> new ResourceNotFoundException("Participant user not found"));
            usersById.put(participantUserId, user);
        }

        receipt.getItems().clear();
        receipt.getParticipants().removeIf(participant -> !participant.getUser().getId().equals(ownerId));
        receiptRepository.saveAndFlush(receipt);

        Map<Long, ReceiptParticipant> participantsByUserId = new HashMap<>();
        ReceiptParticipant ownerParticipant = receipt.getParticipants().stream()
                .filter(participant -> participant.getUser().getId().equals(ownerId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Receipt owner must be a participant"));
        participantsByUserId.put(ownerId, ownerParticipant);
        for (Long participantUserId : participantUserIds) {
            ReceiptParticipant participant = new ReceiptParticipant();
            participant.setReceipt(receipt);
            participant.setUser(usersById.get(participantUserId));
            receipt.getParticipants().add(participant);
            participantsByUserId.put(participantUserId, participant);
        }

        for (SaveReceiptDraftItemRequest itemRequest : request.getItems()) {
            ReceiptItem item = new ReceiptItem();
            item.setReceipt(receipt);
            item.setName(itemRequest.getName());
            item.setQuantity(itemRequest.getQuantity());
            item.setUnitPrice(itemRequest.getUnitPrice());
            item.setTotal(itemRequest.getQuantity().multiply(itemRequest.getUnitPrice())
                    .setScale(2, RoundingMode.HALF_UP));

            Set<Long> allocationUserIds = new LinkedHashSet<>();
            for (SaveReceiptDraftAllocationRequest allocationRequest : itemRequest.getAllocations()) {
                if (!allocationUserIds.add(allocationRequest.getUserId())) {
                    throw new IllegalArgumentException("A participant can only have one allocation per item");
                }
                ReceiptParticipant participant = participantsByUserId.get(allocationRequest.getUserId());
                if (participant == null) {
                    throw new IllegalArgumentException("Every allocation user must be the owner or a participant");
                }
                validateAllocationTypeAndTotals(item, item.getAllocations(), allocationRequest.getAllocationType(),
                        allocationRequest.getInputValue());
                ReceiptItemAllocation allocation = new ReceiptItemAllocation();
                allocation.setReceiptItem(item);
                allocation.setParticipant(participant);
                allocation.setAllocationType(allocationRequest.getAllocationType());
                allocation.setInputValue(allocationRequest.getInputValue());
                item.getAllocations().add(allocation);
            }
            receipt.getItems().add(item);
        }

        receipt.setMerchantName(request.getMerchantName());
        receipt.setPurchaseDate(request.getPurchaseDate());
        receipt.setDiscount(request.getDiscount().setScale(2, RoundingMode.HALF_UP));
        receipt.setTax(request.getTax().setScale(2, RoundingMode.HALF_UP));
        receipt.setFee(request.getFee().setScale(2, RoundingMode.HALF_UP));
        receipt.setTip(request.getTip().setScale(2, RoundingMode.HALF_UP));
        receipt.setCurrency(request.getCurrency());
        recalculateReceiptTotal(receipt);
        clearCalculatedAmounts(receipt);
        return receiptRepository.saveAndFlush(receipt);
    }

    @Transactional
    public void deleteReceiptItem(Long receiptId, Long itemId, Long ownerId) {
        Receipt receipt = findOwnedDraft(receiptId, ownerId);
        if (receipt.getItems().size() == 1) {
            throw new IllegalArgumentException("A receipt must have at least one item");
        }
        receipt.getItems().remove(findItem(receipt, itemId));
        recalculateReceiptTotal(receipt);
        clearCalculatedAmounts(receipt);
        receiptRepository.saveAndFlush(receipt);
    }

    @Transactional
    public ReceiptItemAllocation updateItemAllocation(Long receiptId, Long itemId, Long allocationId,
                                                       Long ownerId, AllocationType type, BigDecimal inputValue) {
        Receipt receipt = findOwnedDraft(receiptId, ownerId);
        ReceiptItem item = findItem(receipt, itemId);
        ReceiptItemAllocation allocation = item.getAllocations().stream()
                .filter(candidate -> candidate.getId().equals(allocationId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Receipt item allocation not found"));

        List<ReceiptItemAllocation> otherAllocations = item.getAllocations().stream()
                .filter(candidate -> !candidate.getId().equals(allocationId))
                .toList();
        validateAllocationTypeAndTotals(item, otherAllocations, type, inputValue);
        allocation.setAllocationType(type);
        allocation.setInputValue(inputValue);
        clearCalculatedAmounts(receipt);
        return receiptItemAllocationRepository.saveAndFlush(allocation);
    }

    @Transactional
    public void deleteItemAllocation(Long receiptId, Long itemId, Long allocationId, Long ownerId) {
        Receipt receipt = findOwnedDraft(receiptId, ownerId);
        ReceiptItem item = findItem(receipt, itemId);
        ReceiptItemAllocation allocation = item.getAllocations().stream()
                .filter(candidate -> candidate.getId().equals(allocationId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Receipt item allocation not found"));
        item.getAllocations().remove(allocation);
        clearCalculatedAmounts(receipt);
        receiptRepository.saveAndFlush(receipt);
    }

    @Transactional
    public void removeParticipant(Long receiptId, Long participantId, Long ownerId) {
        Receipt receipt = findOwnedDraft(receiptId, ownerId);
        ReceiptParticipant participant = findParticipant(receipt, participantId);
        if (participant.getUser().getId().equals(receipt.getOwner().getId())) {
            throw new IllegalArgumentException("The receipt owner cannot be removed as a participant");
        }
        boolean hasAllocations = receipt.getItems().stream()
                .flatMap(item -> item.getAllocations().stream())
                .anyMatch(allocation -> allocation.getParticipant().getId().equals(participantId));
        if (hasAllocations) {
            throw new IllegalArgumentException("Remove the participant's allocations before removing the participant");
        }
        receipt.getParticipants().remove(participant);
        clearCalculatedAmounts(receipt);
        receiptRepository.saveAndFlush(receipt);
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

    @Transactional
    public void deleteReceipt(Long receiptId, Long ownerId) {
        Receipt receipt = findOwned(receiptId, ownerId);
        if (receipt.getStatus() == ReceiptStatus.SETTLED) {
            throw new IllegalArgumentException("Settled receipts cannot be deleted");
        }
        receiptRepository.delete(receipt);
        receiptRepository.flush();
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

    private ReceiptItem findItem(Receipt receipt, Long itemId) {
        return receipt.getItems().stream()
                .filter(candidate -> candidate.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Receipt item not found"));
    }

    private ReceiptParticipant findParticipant(Receipt receipt, Long participantId) {
        return receipt.getParticipants().stream()
                .filter(candidate -> candidate.getId().equals(participantId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Receipt participant not found"));
    }

    private void validateAllocationTypeAndTotals(ReceiptItem item,
                                                  List<ReceiptItemAllocation> existingAllocations,
                                                  AllocationType type, BigDecimal inputValue) {
        boolean usesAnotherAllocationType = existingAllocations.stream()
                .map(ReceiptItemAllocation::getAllocationType)
                .anyMatch(existingType -> existingType != type);
        if (usesAnotherAllocationType) {
            throw new IllegalArgumentException("All allocations for an item must use the same allocation type");
        }
        BigDecimal allocatedInputTotal = existingAllocations.stream()
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
    }

    private void recalculateReceiptTotal(Receipt receipt) {
        BigDecimal subtotal = receipt.getItems().stream()
                .map(ReceiptItem::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        receipt.setSubtotal(subtotal);
        receipt.setTotal(subtotal.subtract(receipt.getDiscount())
                .add(receipt.getTax())
                .add(receipt.getFee())
                .add(receipt.getTip())
                .setScale(2, RoundingMode.HALF_UP));
    }

    private void clearCalculatedAmounts(Receipt receipt) {
        receipt.getItems().forEach(item -> item.getAllocations().forEach(allocation ->
                allocation.setFinalAmount(BigDecimal.ZERO)));
        receipt.getParticipants().forEach(participant ->
                participant.setFinalOwedAmount(BigDecimal.ZERO));
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
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found"));
        if (!receipt.getOwner().getId().equals(ownerId)) {
            throw new ForbiddenOperationException("Only the receipt owner can access this receipt");
        }
        return receipt;
    }
}
