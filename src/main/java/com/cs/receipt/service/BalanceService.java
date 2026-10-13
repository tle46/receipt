package com.cs.receipt.service;
import com.cs.receipt.dto.*;
import com.cs.receipt.exception.ResourceNotFoundException;
import com.cs.receipt.model.*;
import com.cs.receipt.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
@Service
public class BalanceService {
    private final ReceiptRepository receipts;
    private final UserRepository users;
    public BalanceService(ReceiptRepository receipts, UserRepository users) { this.receipts = receipts; this.users = users; }
    private void requireUser(Long id) {
        if (!users.existsById(id)) throw new ResourceNotFoundException("User not found");
    }
    @Transactional(readOnly = true)
    public BalanceResponse getBalances(Long userId) {
        requireUser(userId);
        Map<String, Map<Long, Person>> currencies = new TreeMap<>();
        for (Receipt receipt : receipts.findVisibleToUser(userId, List.of(ReceiptStatus.FINALIZED, ReceiptStatus.SETTLED))) {
            if (receipt.getStatus() != ReceiptStatus.FINALIZED) continue;
            boolean owner = receipt.getOwner().getId().equals(userId);
            for (ReceiptParticipant p : receipt.getParticipants()) {
                if (p.isSettled() || p.getFinalOwedAmount().signum() == 0
                        || p.getUser().getId().equals(receipt.getOwner().getId())
                        || (!owner && !p.getUser().getId().equals(userId))) continue;
                User other = owner ? p.getUser() : receipt.getOwner();
                Person person = currencies.computeIfAbsent(receipt.getCurrency(), k -> new TreeMap<>())
                        .computeIfAbsent(other.getId(), k -> new Person(other));
                if (owner) person.owed = person.owed.add(p.getFinalOwedAmount());
                else person.owing = person.owing.add(p.getFinalOwedAmount());
            }
        }
        List<BalanceResponse.CurrencyBalance> result = new ArrayList<>();
        currencies.forEach((currency, people) -> {
            var rows = people.values().stream().map(p -> new BalanceResponse.PersonBalance(p.user.getId(),
                    p.user.getUsername(), p.user.getDisplayName(), p.owed, p.owing, p.owed.subtract(p.owing))).toList();
            BigDecimal owed = rows.stream().map(BalanceResponse.PersonBalance::owedToYou).reduce(zero(), BigDecimal::add);
            BigDecimal owing = rows.stream().map(BalanceResponse.PersonBalance::youOwe).reduce(zero(), BigDecimal::add);
            result.add(new BalanceResponse.CurrencyBalance(currency, owed, owing, owed.subtract(owing), rows));
        });
        return new BalanceResponse(userId, result);
    }
    @Transactional
    public SettlePersonResponse settlePerson(Long userId, Long personUserId) {
        requireUser(userId); requireUser(personUserId);
        if (userId.equals(personUserId)) throw new IllegalArgumentException("Cannot settle yourself");
        Map<String, BigDecimal> amounts = new TreeMap<>();
        Map<String, Integer> counts = new TreeMap<>();
        List<Long> ids = new ArrayList<>();
        for (Receipt receipt : receipts.findLockedOutstandingByOwner(userId)) {
            for (ReceiptParticipant p : receipt.getParticipants()) {
                if (!p.getUser().getId().equals(personUserId) || p.isSettled()) continue;
                p.setSettled(true);
                amounts.merge(receipt.getCurrency(), p.getFinalOwedAmount(), BigDecimal::add);
                counts.merge(receipt.getCurrency(), 1, Integer::sum);
                ids.add(receipt.getId());
                ReceiptService.updateSettlementStatus(receipt);
            }
        }
        return new SettlePersonResponse(personUserId, amounts.entrySet().stream().map(e ->
                new SettlePersonResponse.SettledCurrency(e.getKey(), e.getValue(), counts.get(e.getKey()))).toList(), ids);
    }
    private static BigDecimal zero() { return new BigDecimal("0.00"); }
    private static class Person {
        final User user;
        BigDecimal owed = zero(), owing = zero();
        Person(User user) { this.user = user; }
    }
}
