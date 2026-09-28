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
    private BigDecimal total;
    private Long ownerId;
    private List<ReceiptItemResponse> items;

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
            BigDecimal total,
            Long ownerId,
            List<ReceiptItemResponse> items) {

        this.id = id;
        this.merchantName = merchantName;
        this.purchaseDate = purchaseDate;
        this.subtotal = subtotal;
        this.discount = discount;
        this.tax = tax;
        this.fee = fee;
        this.total = total;
        this.ownerId = ownerId;
        this.items = items;
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

    public BigDecimal getTotal() {
        return total;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public List<ReceiptItemResponse> getItems() {
        return items;
    }

    public static class ReceiptItemResponse {

        private Long id;
        private String name;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal total;

        public ReceiptItemResponse() {
        }

        public ReceiptItemResponse(
                Long id,
                String name,
                BigDecimal quantity,
                BigDecimal unitPrice,
                BigDecimal total) {

            this.id = id;
            this.name = name;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.total = total;
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
    }

    public static ReceiptResponse fromReceipt(Receipt receipt) {

        List<ReceiptItemResponse> items = receipt.getItems()
                .stream()
                .map(item -> new ReceiptItemResponse(
                        item.getId(),
                        item.getName(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getTotal()
                ))
                .toList();

        return new ReceiptResponse(
                receipt.getId(),
                receipt.getMerchantName(),
                receipt.getPurchaseDate(),
                receipt.getSubtotal(),
                receipt.getDiscount(),
                receipt.getTax(),
                receipt.getFee(),
                receipt.getTotal(),
                receipt.getOwner().getId(),
                items
        );
    }
}