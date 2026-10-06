package com.cs.receipt.dto;

import com.cs.receipt.model.ReceiptAdjustmentType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** An exact manual amount for one person's share of a receipt-level charge. */
public class SaveReceiptDraftAdjustmentAllocationRequest {
    @NotNull(message = "Adjustment type is required")
    private ReceiptAdjustmentType adjustmentType;
    @NotNull(message = "Participant user ID is required")
    private Long userId;
    @NotNull(message = "Adjustment amount is required")
    @DecimalMin(value = "0.00", message = "Adjustment amount cannot be negative")
    private BigDecimal amount;

    public ReceiptAdjustmentType getAdjustmentType() { return adjustmentType; }
    public void setAdjustmentType(ReceiptAdjustmentType adjustmentType) { this.adjustmentType = adjustmentType; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
