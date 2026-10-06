package com.cs.receipt.dto;

import com.cs.receipt.model.Receipt;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class ReceiptResponse {

    private Long id;
    private String merchantName;
    private LocalDateTime purchaseDate;
    private BigDecimal subtotal;
    private BigDecimal discount;
    private BigDecimal tax;
    private BigDecimal fee;
    private BigDecimal tip;
    private BigDecimal total;
    private String currency;
    private String status;
    private Long ownerId;
    private List<ReceiptItemResponse> items;
    private List<ReceiptParticipantResponse> participants;
    private List<ReceiptAdjustmentAllocationResponse> adjustmentAllocations;

    public ReceiptResponse() {
    }

    public ReceiptResponse(
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

        this.id = id;
        this.merchantName = merchantName;
        this.purchaseDate = purchaseDate;
        this.subtotal = subtotal;
        this.discount = discount;
        this.tax = tax;
        this.fee = fee;
        this.tip = tip;
        this.total = total;
        this.currency = currency;
        this.status = status;
        this.ownerId = ownerId;
        this.items = items;
        this.participants = participants;
        this.adjustmentAllocations = adjustmentAllocations;
    }

    public Long getId() {
        return id;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public LocalDateTime getPurchaseDate() {
        return purchaseDate;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getDiscount() {
        return discount;
    }

    public BigDecimal getTax() {
        return tax;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public BigDecimal getTip() { return tip; }

    public BigDecimal getTotal() {
        return total;
    }

    public String getCurrency() { return currency; }

    public String getStatus() { return status; }

    public Long getOwnerId() {
        return ownerId;
    }

    public List<ReceiptItemResponse> getItems() {
        return items;
    }

    public List<ReceiptParticipantResponse> getParticipants() {
        return participants;
    }

    public List<ReceiptAdjustmentAllocationResponse> getAdjustmentAllocations() { return adjustmentAllocations; }

    public static class ReceiptItemResponse {

        private Long id;
        private String name;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal total;
        private List<ReceiptItemAllocationResponse> allocations;

        public ReceiptItemResponse() {
        }

        public ReceiptItemResponse(
                Long id,
                String name,
                BigDecimal quantity,
                BigDecimal unitPrice,
                BigDecimal total,
                List<ReceiptItemAllocationResponse> allocations) {

            this.id = id;
            this.name = name;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.total = total;
            this.allocations = allocations;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public BigDecimal getQuantity() {
            return quantity;
        }

        public BigDecimal getUnitPrice() {
            return unitPrice;
        }

        public BigDecimal getTotal() {
            return total;
        }

        public List<ReceiptItemAllocationResponse> getAllocations() {
            return allocations;
        }

        public static ReceiptItemResponse from(com.cs.receipt.model.ReceiptItem item) {
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
