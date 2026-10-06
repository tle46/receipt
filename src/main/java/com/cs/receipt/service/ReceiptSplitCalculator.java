package com.cs.receipt.service;

import com.cs.receipt.model.AllocationType;
import com.cs.receipt.model.Receipt;
import com.cs.receipt.model.ReceiptAdjustmentAllocation;
import com.cs.receipt.model.ReceiptAdjustmentType;
import com.cs.receipt.model.ReceiptItem;
import com.cs.receipt.model.ReceiptItemAllocation;
import com.cs.receipt.model.ReceiptParticipant;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Applies split rules and cent rounding without database operations. */
@Component
public class ReceiptSplitCalculator {
    public void calculate(Receipt receipt) {
        Map<ReceiptParticipant, BigDecimal> itemSubtotals = new HashMap<>();
        receipt.getParticipants().forEach(participant ->
                itemSubtotals.put(participant, BigDecimal.ZERO.setScale(2)));

        for (ReceiptItem item : receipt.getItems()) {
            calculateItemAllocations(item);
            for (ReceiptItemAllocation allocation : item.getAllocations()) {
                itemSubtotals.merge(allocation.getParticipant(), allocation.getFinalAmount(), BigDecimal::add);
            }
        }

        Map<ReceiptParticipant, BigDecimal> discounts = distributeAmount(receipt.getDiscount(), itemSubtotals);
        Map<ReceiptParticipant, BigDecimal> taxes = distributeAdjustment(receipt, ReceiptAdjustmentType.TAX,
                receipt.getTax(), itemSubtotals);
        Map<ReceiptParticipant, BigDecimal> fees = distributeAdjustment(receipt, ReceiptAdjustmentType.FEE,
                receipt.getFee(), itemSubtotals);
        Map<ReceiptParticipant, BigDecimal> tips = distributeAdjustment(receipt, ReceiptAdjustmentType.TIP,
                receipt.getTip(), itemSubtotals);

        for (ReceiptParticipant participant : receipt.getParticipants()) {
            BigDecimal owed = itemSubtotals.get(participant)
                    .subtract(discounts.get(participant))
                    .add(taxes.get(participant))
                    .add(fees.get(participant))
                    .add(tips.get(participant))
                    .setScale(2, RoundingMode.HALF_UP);
            participant.setFinalOwedAmount(owed);
        }
    }

    private void calculateItemAllocations(ReceiptItem item) {
        List<ReceiptItemAllocation> allocations = item.getAllocations();
        if (allocations.isEmpty()) {
            throw new IllegalArgumentException("Every item must have at least one allocation: " + item.getName());
        }

        AllocationType type = allocations.getFirst().getAllocationType();
        if (allocations.stream().anyMatch(allocation -> allocation.getAllocationType() != type)) {
            throw new IllegalArgumentException("All allocations for an item must use the same allocation type");
        }

        List<BigDecimal> weights = switch (type) {
            case EXACT -> exactWeights(item, allocations);
            case EQUAL -> allocations.stream().map(allocation -> BigDecimal.ONE).toList();
            case PERCENTAGE -> percentageWeights(allocations);
            case SHARES -> shareWeights(allocations);
        };
        List<BigDecimal> amounts = distributeByWeights(item.getTotal(), allocations, weights);
        for (int index = 0; index < allocations.size(); index++) {
            allocations.get(index).setFinalAmount(amounts.get(index));
        }
    }

    private List<BigDecimal> exactWeights(ReceiptItem item, List<ReceiptItemAllocation> allocations) {
        BigDecimal total = allocations.stream().map(ReceiptItemAllocation::getInputValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(item.getTotal()) != 0) {
            throw new IllegalArgumentException("Exact allocations must equal the item total: " + item.getName());
        }
        return allocations.stream().map(ReceiptItemAllocation::getInputValue).toList();
    }

    private List<BigDecimal> percentageWeights(List<ReceiptItemAllocation> allocations) {
        BigDecimal total = allocations.stream().map(ReceiptItemAllocation::getInputValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(new BigDecimal("100")) != 0) {
            throw new IllegalArgumentException("Percentage allocations must equal 100");
        }
        return allocations.stream().map(ReceiptItemAllocation::getInputValue).toList();
    }

    private List<BigDecimal> shareWeights(List<ReceiptItemAllocation> allocations) {
        BigDecimal total = allocations.stream().map(ReceiptItemAllocation::getInputValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) {
            throw new IllegalArgumentException("Share allocations must have a positive total");
        }
        return allocations.stream().map(ReceiptItemAllocation::getInputValue).toList();
    }

    private Map<ReceiptParticipant, BigDecimal> distributeAmount(
            BigDecimal amount, Map<ReceiptParticipant, BigDecimal> weights) {
        List<ReceiptParticipant> participants = new ArrayList<>(weights.keySet());
        participants.sort(Comparator.comparing(ReceiptParticipant::getId));
        List<BigDecimal> values = distributeByWeights(amount, participants,
                participants.stream().map(weights::get).toList());
        Map<ReceiptParticipant, BigDecimal> result = new HashMap<>();
        for (int index = 0; index < participants.size(); index++) {
            result.put(participants.get(index), values.get(index));
        }
        return result;
    }

    private Map<ReceiptParticipant, BigDecimal> distributeAdjustment(Receipt receipt,
                                                                        ReceiptAdjustmentType type,
                                                                        BigDecimal amount,
                                                                        Map<ReceiptParticipant, BigDecimal> defaults) {
        List<ReceiptAdjustmentAllocation> overrides = receipt.getAdjustmentAllocations().stream()
                .filter(allocation -> allocation.getAdjustmentType() == type)
                .toList();
        if (overrides.isEmpty()) {
            return distributeAmount(amount, defaults);
        }

        BigDecimal overrideTotal = overrides.stream().map(ReceiptAdjustmentAllocation::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        if (overrideTotal.compareTo(amount) != 0) {
            throw new IllegalArgumentException("Manual " + type.name().toLowerCase()
                    + " allocations must equal " + amount.setScale(2, RoundingMode.HALF_UP));
        }

        Map<ReceiptParticipant, BigDecimal> result = new HashMap<>();
        receipt.getParticipants().forEach(participant -> result.put(participant, BigDecimal.ZERO.setScale(2)));
        overrides.forEach(allocation -> result.put(allocation.getParticipant(), allocation.getAmount()
                .setScale(2, RoundingMode.HALF_UP)));
        return result;
    }

    private <T> List<BigDecimal> distributeByWeights(
            BigDecimal total, List<T> recipients, List<BigDecimal> weights) {
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("Cannot distribute an amount without participants");
        }
        BigDecimal weightTotal = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (weightTotal.signum() == 0) {
            weights = recipients.stream().map(recipient -> BigDecimal.ONE).toList();
            weightTotal = BigDecimal.valueOf(recipients.size());
        }
        BigDecimal finalWeightTotal = weightTotal;

        List<BigDecimal> unrounded = weights.stream()
                .map(weight -> total.multiply(weight).divide(finalWeightTotal, 12, RoundingMode.HALF_UP))
                .toList();
        List<BigDecimal> rounded = new ArrayList<>(unrounded.stream()
                .map(value -> value.setScale(2, RoundingMode.DOWN)).toList());
        BigDecimal remainder = total.subtract(rounded.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        int centsToAssign = remainder.movePointRight(2).intValueExact();
        if (centsToAssign < 0) {
            throw new IllegalStateException("Unexpected negative rounding remainder");
        }

        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < recipients.size(); index++) {
            order.add(index);
        }
        order.sort(Comparator.<Integer, BigDecimal>comparing(index ->
                        unrounded.get(index).subtract(rounded.get(index)))
                .reversed().thenComparingInt(index -> index));
        for (int index = 0; index < centsToAssign; index++) {
            int recipientIndex = order.get(index % order.size());
            rounded.set(recipientIndex, rounded.get(recipientIndex).add(new BigDecimal("0.01")));
        }
        return rounded;
    }

}
