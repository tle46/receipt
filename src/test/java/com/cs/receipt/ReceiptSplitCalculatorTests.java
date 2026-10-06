package com.cs.receipt;

import com.cs.receipt.model.AllocationType;
import com.cs.receipt.model.Receipt;
import com.cs.receipt.model.ReceiptAdjustmentAllocation;
import com.cs.receipt.model.ReceiptAdjustmentType;
import com.cs.receipt.model.ReceiptItem;
import com.cs.receipt.model.ReceiptItemAllocation;
import com.cs.receipt.model.ReceiptParticipant;
import com.cs.receipt.service.ReceiptSplitCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceiptSplitCalculatorTests {
    private final ReceiptSplitCalculator calculator = new ReceiptSplitCalculator();

    @ParameterizedTest
    @CsvSource({
            "EQUAL,1,1,5.01,5.00",
            "EXACT,3.34,6.67,3.34,6.67",
            "PERCENTAGE,25,75,2.50,7.51",
            "SHARES,1,2,3.34,6.67"
    })
    void roundsEachSplitToCentsAndPreservesTheTotal(AllocationType type, String firstInput,
                                                   String secondInput, String firstOwed, String secondOwed) {
        Receipt receipt = receipt(type, firstInput, secondInput);
        calculator.calculate(receipt);
        assertThat(receipt.getParticipants().get(0).getFinalOwedAmount()).isEqualByComparingTo(firstOwed);
        assertThat(receipt.getParticipants().get(1).getFinalOwedAmount()).isEqualByComparingTo(secondOwed);
        assertThat(receipt.getParticipants().stream().map(ReceiptParticipant::getFinalOwedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("10.01");
    }

    @Test
    void distributesTaxProportionallyAndHonorsManualTipOverridesWithoutDatabaseAccess() {
        Receipt receipt = receipt(AllocationType.EXACT, "3.34", "6.67");
        receipt.setTax(new BigDecimal("1.00"));
        receipt.setTip(new BigDecimal("2.00"));
        var override = new ReceiptAdjustmentAllocation();
        override.setReceipt(receipt);
        override.setParticipant(receipt.getParticipants().get(0));
        override.setAdjustmentType(ReceiptAdjustmentType.TIP);
        override.setAmount(new BigDecimal("2.00"));
        receipt.getAdjustmentAllocations().add(override);
        calculator.calculate(receipt);
        assertThat(receipt.getParticipants().get(0).getFinalOwedAmount()).isEqualByComparingTo("5.67");
        assertThat(receipt.getParticipants().get(1).getFinalOwedAmount()).isEqualByComparingTo("7.34");
    }

    @Test
    void rejectsIncompleteSplits() {
        Receipt receipt = receipt(AllocationType.EXACT, "3.00", "6.00");
        assertThatThrownBy(() -> calculator.calculate(receipt)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Exact allocations must equal the item total");
    }

    private Receipt receipt(AllocationType type, String firstInput, String secondInput) {
        var receipt = new Receipt();
        receipt.setDiscount(BigDecimal.ZERO);
        receipt.setTax(BigDecimal.ZERO);
        receipt.setFee(BigDecimal.ZERO);
        var item = new ReceiptItem();
        item.setName("Dinner");
        item.setReceipt(receipt);
        item.setTotal(new BigDecimal("10.01"));
        receipt.getItems().add(item);
        String[] inputs = {firstInput, secondInput};
        for (int index = 0; index < inputs.length; index++) {
            var participant = new ReceiptParticipant();
            // IDs give cent assignment the same stable ordering as persisted participants.
            ReflectionTestUtils.setField(participant, "id", (long) index + 1);
            participant.setReceipt(receipt);
            receipt.getParticipants().add(participant);
            var allocation = new ReceiptItemAllocation();
            allocation.setReceiptItem(item);
            allocation.setParticipant(participant);
            allocation.setAllocationType(type);
            allocation.setInputValue(new BigDecimal(inputs[index]));
            item.getAllocations().add(allocation);
        }
        return receipt;
    }
}
