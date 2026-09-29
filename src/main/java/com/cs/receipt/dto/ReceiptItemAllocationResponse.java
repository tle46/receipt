package com.cs.receipt.dto;

import com.cs.receipt.model.ReceiptItemAllocation;
import java.math.BigDecimal;

public record ReceiptItemAllocationResponse(Long id, Long itemId, Long participantId,
                                            String allocationType, BigDecimal inputValue,
                                            BigDecimal finalAmount) {
    public static ReceiptItemAllocationResponse from(ReceiptItemAllocation allocation) {
        return new ReceiptItemAllocationResponse(allocation.getId(), allocation.getReceiptItem().getId(),
                allocation.getParticipant().getId(), allocation.getAllocationType().name(),
                allocation.getInputValue(), allocation.getFinalAmount());
    }
}
