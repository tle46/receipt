package com.cs.receipt.dto;

import com.cs.receipt.model.AllocationType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public class CreateReceiptItemAllocationRequest {
    @NotNull(message = "Participant ID is required")
    private Long participantId;

    @NotNull(message = "Allocation type is required")
    private AllocationType allocationType;

    @NotNull(message = "Allocation value is required")
    @DecimalMin(value = "0.0001", message = "Allocation value must be greater than 0")
    private BigDecimal inputValue;

    public Long getParticipantId() { return participantId; }
    public void setParticipantId(Long participantId) { this.participantId = participantId; }
    public AllocationType getAllocationType() { return allocationType; }
    public void setAllocationType(AllocationType allocationType) { this.allocationType = allocationType; }
    public BigDecimal getInputValue() { return inputValue; }
    public void setInputValue(BigDecimal inputValue) { this.inputValue = inputValue; }
}
