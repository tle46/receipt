package com.cs.receipt.service;

import com.cs.receipt.model.Receipt;
import com.cs.receipt.model.ReceiptItem;
import com.cs.receipt.model.ReceiptParticipant;
import com.cs.receipt.model.ReceiptItemAllocation;
import com.cs.receipt.model.AllocationType;
import com.cs.receipt.model.ReceiptStatus;
import com.cs.receipt.model.ReceiptAdjustmentAllocation;
import com.cs.receipt.model.User;
import com.cs.receipt.dto.ReceiptResponse;
import com.cs.receipt.dto.SaveReceiptDraftAllocationRequest;
import com.cs.receipt.dto.SaveReceiptDraftItemRequest;
import com.cs.receipt.dto.SaveReceiptDraftRequest;
import com.cs.receipt.dto.SaveReceiptDraftAdjustmentAllocationRequest;
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
import java.time.LocalDateTime;
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
    private final ReceiptSplitCalculator splitCalculator;

    public ReceiptService(
            ReceiptRepository receiptRepository,
            UserRepository userRepository,
            ReceiptParticipantRepository receiptParticipantRepository,
            ReceiptItemAllocationRepository receiptItemAllocationRepository,
            ReceiptItemRepository receiptItemRepository,
            ReceiptSplitCalculator splitCalculator) {

        this.receiptRepository = receiptRepository;
        this.userRepository = userRepository;
        this.receiptParticipantRepository = receiptParticipantRepository;
        this.receiptItemAllocationRepository = receiptItemAllocationRepository;
        this.receiptItemRepository = receiptItemRepository;
        this.splitCalculator = splitCalculator;
    }

    @Transactional
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

            BigDecimal calculatedItemTotal = itemTotal(item.getQuantity(), item.getUnitPrice());

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

        BigDecimal calculatedTotal = receiptTotal(calculatedSubtotal, discount, tax, fee, tip);
        applyReceiptDetails(receipt, receipt.getMerchantName(), receipt.getPurchaseDate(),
                discount, tax, fee, tip, receipt.getCurrency());

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
        return ReceiptResponse.fromReceipt(findViewable(receiptId, userId));
    }

    @Transactional(readOnly = true)
    public List<ReceiptResponse> listReceipts(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User not found");
        }
        return receiptRepository.findDistinctByOwnerIdOrParticipantsUserId(userId, userId).stream()
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
        return receiptParticipantRepository.save(participant);
    }

    @Transactional
    public ReceiptItemAllocation addItemAllocation(Long receiptId, Long itemId, Long ownerId,
                                                    Long participantId, AllocationType type,
                                                    BigDecimal inputValue) {
        Receipt receipt = findOwnedDraft(receiptId, ownerId);
        ReceiptItem item = findItem(receipt, itemId);
        ReceiptParticipant participant = findParticipant(receipt, participantId);

        boolean alreadyAllocated = item.getAllocations().stream()
                .anyMatch(allocation -> allocation.getParticipant().getId().equals(participantId));
        if (alreadyAllocated) {
            throw new IllegalArgumentException("Participant already has an allocation for this item");
        }

        validateAllocationTypeAndTotals(item, item.getAllocations(), type, inputValue);

        ReceiptItemAllocation allocation = new ReceiptItemAllocation();
        allocation.setReceiptItem(item);
        allocation.setParticipant(participant);
        allocation.setAllocationType(type);
        allocation.setInputValue(inputValue);
        item.getAllocations().add(allocation);
        clearCalculatedAmounts(receipt);
        return receiptItemAllocationRepository.save(allocation);
    }

    @Transactional
    public ReceiptItem updateReceiptItem(Long receiptId, Long itemId, Long ownerId,
                                         String name, BigDecimal quantity, BigDecimal unitPrice) {
        Receipt receipt = findOwnedDraft(receiptId, ownerId);
        ReceiptItem item = findItem(receipt, itemId);
        applyItemDetails(item, name, quantity, unitPrice);
        recalculateReceiptTotal(receipt);
        clearCalculatedAmounts(receipt);
        return item;
    }

    @Transactional
    public ReceiptItem addReceiptItem(Long receiptId, Long ownerId, String name,
                                      BigDecimal quantity, BigDecimal unitPrice) {
        Receipt receipt = findOwnedDraft(receiptId, ownerId);
        ReceiptItem item = new ReceiptItem();
        item.setReceipt(receipt);
        applyItemDetails(item, name, quantity, unitPrice);
        receipt.getItems().add(item);
        recalculateReceiptTotal(receipt);
        clearCalculatedAmounts(receipt);
        return receiptItemRepository.save(item);
    }

    @Transactional
    public Receipt updateReceiptDetails(Long receiptId, Long ownerId, String merchantName,
                                        LocalDateTime purchaseDate, BigDecimal discount,
                                        BigDecimal tax, BigDecimal fee, BigDecimal tip, String currency) {
        Receipt receipt = findOwnedDraft(receiptId, ownerId);
        applyReceiptDetails(receipt, merchantName, purchaseDate, discount, tax, fee, tip, currency);
        recalculateReceiptTotal(receipt);
        clearCalculatedAmounts(receipt);
        return receipt;
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
        receipt.getAdjustmentAllocations().clear();
        receipt.getParticipants().removeIf(participant -> !participant.getUser().getId().equals(ownerId));
        // Delete old participants before inserting replacements with the same unique user keys.
        receiptRepository.flush();

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
            applyItemDetails(item, itemRequest.getName(), itemRequest.getQuantity(), itemRequest.getUnitPrice());

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

        applyReceiptDetails(receipt, request.getMerchantName(), request.getPurchaseDate(), request.getDiscount(),
                request.getTax(), request.getFee(), request.getTip(), request.getCurrency());
        replaceAdjustmentAllocations(receipt, participantsByUserId, request.getAdjustmentAllocations());
        recalculateReceiptTotal(receipt);
        clearCalculatedAmounts(receipt);
        return receipt;
    }

    /**
     * Replaces all receipt-level manual overrides. Omit a charge type to use the default proportional split for it.
     */
    @Transactional
    public Receipt saveAdjustmentAllocations(Long receiptId, Long ownerId,
                                              List<SaveReceiptDraftAdjustmentAllocationRequest> allocations) {
        Receipt receipt = findOwnedDraft(receiptId, ownerId);
        Map<Long, ReceiptParticipant> participantsByUserId = participantsByUserId(receipt);
        receipt.getAdjustmentAllocations().clear();
        replaceAdjustmentAllocations(receipt, participantsByUserId, allocations);
        clearCalculatedAmounts(receipt);
        return receipt;
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
        return allocation;
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
        boolean hasAdjustmentAllocations = receipt.getAdjustmentAllocations().stream()
                .anyMatch(allocation -> allocation.getParticipant().getId().equals(participantId));
        if (hasAllocations || hasAdjustmentAllocations) {
            throw new IllegalArgumentException("Remove the participant's allocations (item and adjustment) before removing them");
        }
        receipt.getParticipants().remove(participant);
        clearCalculatedAmounts(receipt);
    }

    /**
     * Calculates and persists the current draft split without changing its status.
     * Every item must have a complete set of allocations using one allocation type.
     */
    @Transactional
    public Receipt calculateSplit(Long receiptId, Long ownerId) {
        Receipt receipt = findOwnedDraft(receiptId, ownerId);
        splitCalculator.calculate(receipt);
        return receipt;
    }

    /** Calculates the draft one final time, then locks its split for review and payment. */
    @Transactional
    public Receipt finalizeReceipt(Long receiptId, Long ownerId) {
        Receipt receipt = calculateSplit(receiptId, ownerId);
        receipt.setStatus(ReceiptStatus.FINALIZED);
        return receipt;
    }

    /** Reopens a finalized receipt so its items and allocations can be revised. */
    @Transactional
    public Receipt reopenReceipt(Long receiptId, Long ownerId) {
        Receipt receipt = findOwned(receiptId, ownerId);
        if (receipt.getStatus() != ReceiptStatus.FINALIZED) {
            throw new IllegalArgumentException("Only finalized receipts can be reopened");
        }
        receipt.setStatus(ReceiptStatus.DRAFT);
        return receipt;
    }

    /** Marks a finalized receipt as paid. Settled receipts cannot be reopened or edited. */
    @Transactional
    public Receipt settleReceipt(Long receiptId, Long ownerId) {
        Receipt receipt = findOwned(receiptId, ownerId);
        if (receipt.getStatus() != ReceiptStatus.FINALIZED) {
            throw new IllegalArgumentException("Only finalized receipts can be settled");
        }
        receipt.setStatus(ReceiptStatus.SETTLED);
        return receipt;
    }

    @Transactional
    public void deleteReceipt(Long receiptId, Long ownerId) {
        Receipt receipt = findOwned(receiptId, ownerId);
        if (receipt.getStatus() == ReceiptStatus.SETTLED) {
            throw new IllegalArgumentException("Settled receipts cannot be deleted");
        }
        receiptRepository.delete(receipt);
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
        receipt.setTotal(receiptTotal(subtotal, receipt.getDiscount(), receipt.getTax(), receipt.getFee(), receipt.getTip()));
    }

    private BigDecimal itemTotal(BigDecimal quantity, BigDecimal unitPrice) {
        return quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal receiptTotal(BigDecimal subtotal, BigDecimal discount, BigDecimal tax,
                                     BigDecimal fee, BigDecimal tip) {
        return subtotal.subtract(discount).add(tax).add(fee).add(tip).setScale(2, RoundingMode.HALF_UP);
    }

    private void applyItemDetails(ReceiptItem item, String name, BigDecimal quantity, BigDecimal unitPrice) {
        item.setName(name);
        item.setQuantity(quantity);
        item.setUnitPrice(unitPrice);
        item.setTotal(itemTotal(quantity, unitPrice));
    }

    private void applyReceiptDetails(Receipt receipt, String merchantName, LocalDateTime purchaseDate,
                                      BigDecimal discount, BigDecimal tax, BigDecimal fee, BigDecimal tip,
                                      String currency) {
        receipt.setMerchantName(merchantName);
        receipt.setPurchaseDate(purchaseDate);
        receipt.setDiscount(discount.setScale(2, RoundingMode.HALF_UP));
        receipt.setTax(tax.setScale(2, RoundingMode.HALF_UP));
        receipt.setFee(fee.setScale(2, RoundingMode.HALF_UP));
        receipt.setTip(tip.setScale(2, RoundingMode.HALF_UP));
        receipt.setCurrency(currency);
    }

    private void clearCalculatedAmounts(Receipt receipt) {
        receipt.getItems().forEach(item -> item.getAllocations().forEach(allocation ->
                allocation.setFinalAmount(BigDecimal.ZERO)));
        receipt.getParticipants().forEach(participant ->
                participant.setFinalOwedAmount(BigDecimal.ZERO));
    }

    private Map<Long, ReceiptParticipant> participantsByUserId(Receipt receipt) {
        Map<Long, ReceiptParticipant> participants = new HashMap<>();
        receipt.getParticipants().forEach(participant -> participants.put(participant.getUser().getId(), participant));
        return participants;
    }

    private void replaceAdjustmentAllocations(Receipt receipt,
                                              Map<Long, ReceiptParticipant> participantsByUserId,
                                              List<SaveReceiptDraftAdjustmentAllocationRequest> requests) {
        if (requests == null) {
            return;
        }
        Set<String> seen = new java.util.HashSet<>();
        for (SaveReceiptDraftAdjustmentAllocationRequest request : requests) {
            ReceiptParticipant participant = participantsByUserId.get(request.getUserId());
            if (participant == null) {
                throw new IllegalArgumentException("Every adjustment allocation user must be the owner or a participant");
            }
            String key = request.getAdjustmentType().name() + ":" + request.getUserId();
            if (!seen.add(key)) {
                throw new IllegalArgumentException("A participant can only have one manual allocation per adjustment");
            }
            ReceiptAdjustmentAllocation allocation = new ReceiptAdjustmentAllocation();
            allocation.setReceipt(receipt);
            allocation.setParticipant(participant);
            allocation.setAdjustmentType(request.getAdjustmentType());
            allocation.setAmount(request.getAmount().setScale(2, RoundingMode.HALF_UP));
            receipt.getAdjustmentAllocations().add(allocation);
        }
    }

    private Receipt findOwnedDraft(Long receiptId, Long ownerId) {
        Receipt receipt = findOwned(receiptId, ownerId);
        if (receipt.getStatus() != ReceiptStatus.DRAFT) {
            throw new IllegalArgumentException("Only draft receipts can be modified");
        }
        return receipt;
    }

    private Receipt findViewable(Long receiptId, Long userId) {
        Receipt receipt = receiptRepository.findById(receiptId)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found"));
        boolean isParticipant = receipt.getParticipants().stream()
                .anyMatch(participant -> participant.getUser().getId().equals(userId));
        if (!receipt.getOwner().getId().equals(userId) && !isParticipant) {
            throw new ForbiddenOperationException("Only the receipt owner or a participant can view this receipt");
        }
        return receipt;
    }

    private Receipt findOwned(Long receiptId, Long ownerId) {
        Receipt receipt = receiptRepository.findById(receiptId)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found"));
        if (!receipt.getOwner().getId().equals(ownerId)) {
            throw new ForbiddenOperationException("Only the receipt owner can modify this receipt");
        }
        return receipt;
    }
}
