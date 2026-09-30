package com.cs.receipt.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class SaveReceiptDraftRequest {
    @Size(max = 200, message = "Merchant name must be 200 characters or less")
    private String merchantName;
    private LocalDateTime purchaseDate;
    @NotNull @DecimalMin(value = "0.00", message = "Discount cannot be negative")
    private BigDecimal discount;
    @NotNull @DecimalMin(value = "0.00", message = "Tax cannot be negative")
    private BigDecimal tax;
    @NotNull @DecimalMin(value = "0.00", message = "Fee cannot be negative")
    private BigDecimal fee;
    @NotNull @DecimalMin(value = "0.00", message = "Tip cannot be negative")
    private BigDecimal tip;
    @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO code")
    private String currency;
    @NotNull @Valid
    private List<Long> participantUserIds;
    @NotNull @Size(min = 1, message = "Receipt must have at least one item") @Valid
    private List<SaveReceiptDraftItemRequest> items;

    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
    public LocalDateTime getPurchaseDate() { return purchaseDate; }
    public void setPurchaseDate(LocalDateTime purchaseDate) { this.purchaseDate = purchaseDate; }
    public BigDecimal getDiscount() { return discount; }
    public void setDiscount(BigDecimal discount) { this.discount = discount; }
    public BigDecimal getTax() { return tax; }
    public void setTax(BigDecimal tax) { this.tax = tax; }
    public BigDecimal getFee() { return fee; }
    public void setFee(BigDecimal fee) { this.fee = fee; }
    public BigDecimal getTip() { return tip; }
    public void setTip(BigDecimal tip) { this.tip = tip; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public List<Long> getParticipantUserIds() { return participantUserIds; }
    public void setParticipantUserIds(List<Long> participantUserIds) { this.participantUserIds = participantUserIds; }
    public List<SaveReceiptDraftItemRequest> getItems() { return items; }
    public void setItems(List<SaveReceiptDraftItemRequest> items) { this.items = items; }
}
