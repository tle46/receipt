package com.cs.receipt.dto;

import com.cs.receipt.model.AllocationType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public class SaveReceiptDraftAllocationRequest {
    @NotNull(message = "Participant user ID is required")
    private Long userId;
    @NotNull(message = "Allocation type is required")
    private AllocationType allocationType;
    @NotNull(message = "Allocation value is required")
    @DecimalMin(value = "0.0001", message = "Allocation value must be greater than 0")
    private BigDecimal inputValue;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public AllocationType getAllocationType() { return allocationType; }
    public void setAllocationType(AllocationType allocationType) { this.allocationType = allocationType; }
    public BigDecimal getInputValue() { return inputValue; }
    public void setInputValue(BigDecimal inputValue) { this.inputValue = inputValue; }
}
