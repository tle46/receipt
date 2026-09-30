package com.cs.receipt.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public class SaveReceiptDraftItemRequest {
    @NotBlank(message = "Item name is required")
    private String name;
    @NotNull(message = "Quantity is required")
    @DecimalMin(value = "0.0001", message = "Quantity must be greater than 0")
    private BigDecimal quantity;
    @NotNull(message = "Unit price is required")
    @DecimalMin(value = "0.00", message = "Unit price cannot be negative")
    private BigDecimal unitPrice;
    @NotNull(message = "Allocations are required")
    @Valid
    private List<SaveReceiptDraftAllocationRequest> allocations;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public List<SaveReceiptDraftAllocationRequest> getAllocations() { return allocations; }
    public void setAllocations(List<SaveReceiptDraftAllocationRequest> allocations) { this.allocations = allocations; }
}
