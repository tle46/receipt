package com.cs.receipt.dto;
import java.math.BigDecimal;
import java.util.List;
public record SettlePersonResponse(Long personUserId, List<SettledCurrency> currencies, List<Long> affectedReceiptIds) {
    public record SettledCurrency(String currency, BigDecimal settledAmount, int participantCount) { }
}
