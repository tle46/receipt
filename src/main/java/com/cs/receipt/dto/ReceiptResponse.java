package com.cs.receipt.dto;

import com.cs.receipt.model.Receipt;
import com.cs.receipt.model.ReceiptItem;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record ReceiptResponse(
        Long id,
        String merchantName,
        LocalDateTime purchaseDate,
        BigDecimal subtotal,
        BigDecimal discount,
        BigDecimal tax,
        BigDecimal fee,
        BigDecimal tip,
        BigDecimal total,
        String currency,
        String status,
        Long ownerId,
        List<ReceiptItemResponse> items,
        List<ReceiptParticipantResponse> participants,
        List<ReceiptAdjustmentAllocationResponse> adjustmentAllocations) {

    public record ReceiptItemResponse(Long id, String name, BigDecimal quantity, BigDecimal unitPrice,
                                      BigDecimal total, List<ReceiptItemAllocationResponse> allocations) {
        public static ReceiptItemResponse from(ReceiptItem item) {
            return new ReceiptItemResponse(item.getId(), item.getName(), item.getQuantity(), item.getUnitPrice(),
                    item.getTotal(), item.getAllocations().stream().map(ReceiptItemAllocationResponse::from).toList());
        }
    }

    public static ReceiptResponse fromReceipt(Receipt receipt) {

        List<ReceiptItemResponse> items = receipt.getItems()
                .stream()
                .map(ReceiptItemResponse::from)
                .toList();

        List<ReceiptParticipantResponse> participants = receipt.getParticipants()
                .stream()
                .map(ReceiptParticipantResponse::from)
                .toList();
        List<ReceiptAdjustmentAllocationResponse> adjustmentAllocations = receipt.getAdjustmentAllocations().stream()
                .map(ReceiptAdjustmentAllocationResponse::from)
                .toList();

        return new ReceiptResponse(
                receipt.getId(),
                receipt.getMerchantName(),
                receipt.getPurchaseDate(),
                receipt.getSubtotal(),
                receipt.getDiscount(),
                receipt.getTax(),
                receipt.getFee(),
                receipt.getTip(),
                receipt.getTotal(),
                receipt.getCurrency(),
                receipt.getStatus().name(),
                receipt.getOwner().getId(),
                items,
                participants,
                adjustmentAllocations
        );
    }
}
