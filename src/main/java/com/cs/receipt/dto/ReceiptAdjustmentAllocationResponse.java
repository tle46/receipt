package com.cs.receipt.dto;

import com.cs.receipt.model.ReceiptAdjustmentAllocation;

import java.math.BigDecimal;

public record ReceiptAdjustmentAllocationResponse(Long id, String adjustmentType,
                                                  Long participantId, Long userId, BigDecimal amount) {
    public static ReceiptAdjustmentAllocationResponse from(ReceiptAdjustmentAllocation allocation) {
        return new ReceiptAdjustmentAllocationResponse(allocation.getId(), allocation.getAdjustmentType().name(),
                allocation.getParticipant().getId(), allocation.getParticipant().getUser().getId(),
                allocation.getAmount());
    }
}
