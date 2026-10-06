package com.cs.receipt.dto;

import com.cs.receipt.model.Receipt;

import java.math.BigDecimal;
import java.util.List;

public record ReceiptSplitResponse(
        Long receiptId,
        String status,
        String currency,
        BigDecimal total,
        List<ParticipantSplit> participants,
        List<ItemAllocationSplit> allocations) {

    public static ReceiptSplitResponse from(Receipt receipt) {
        List<ParticipantSplit> participants = receipt.getParticipants().stream()
                .map(participant -> new ParticipantSplit(
                        participant.getId(), participant.getUser().getId(),
                        participant.getUser().getUsername(), participant.getFinalOwedAmount()))
                .toList();
        List<ItemAllocationSplit> allocations = receipt.getItems().stream()
                .flatMap(item -> item.getAllocations().stream())
                .map(allocation -> new ItemAllocationSplit(
                        allocation.getId(), allocation.getReceiptItem().getId(),
                        allocation.getReceiptItem().getName(), allocation.getParticipant().getId(),
                        allocation.getAllocationType().name(), allocation.getInputValue(), allocation.getFinalAmount()))
                .toList();
        return new ReceiptSplitResponse(receipt.getId(), receipt.getStatus().name(), receipt.getCurrency(),
                receipt.getTotal(), participants, allocations);
    }

    public record ParticipantSplit(Long participantId, Long userId, String username, BigDecimal finalOwedAmount) { }

    public record ItemAllocationSplit(Long allocationId, Long itemId, String itemName, Long participantId,
                                      String allocationType, BigDecimal inputValue, BigDecimal finalAmount) { }
}
