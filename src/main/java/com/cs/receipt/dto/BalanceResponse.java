package com.cs.receipt.dto;
import java.math.BigDecimal;
import java.util.List;
public record BalanceResponse(Long userId, List<CurrencyBalance> currencies) {
    public record CurrencyBalance(String currency, BigDecimal totalOwed, BigDecimal totalOwing,
                                  BigDecimal netBalance, List<PersonBalance> people) { }
    public record PersonBalance(Long userId, String username, String displayName,
                                BigDecimal owedToYou, BigDecimal youOwe, BigDecimal netBalance) { }
}
