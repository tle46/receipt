package com.cs.receipt.service;

import com.cs.receipt.model.Receipt;
import com.cs.receipt.model.ReceiptItem;
import com.cs.receipt.model.User;
import com.cs.receipt.repository.ReceiptRepository;
import com.cs.receipt.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class ReceiptService {

    private final ReceiptRepository receiptRepository;
    private final UserRepository userRepository;

    public ReceiptService(
            ReceiptRepository receiptRepository,
            UserRepository userRepository) {

        this.receiptRepository = receiptRepository;
        this.userRepository = userRepository;
    }

    public Receipt createReceipt(Long userId, Receipt receipt) {

        User owner = userRepository.findById(userId)
                .orElseThrow(() ->
                        new IllegalArgumentException("User not found"));

        receipt.setOwner(owner);

        BigDecimal calculatedSubtotal = BigDecimal.ZERO;

        for (ReceiptItem item : receipt.getItems()) {

            BigDecimal calculatedItemTotal = item.getQuantity()
                    .multiply(item.getUnitPrice())
                    .setScale(2, RoundingMode.HALF_UP);

            BigDecimal providedItemTotal = item.getTotal()
                    .setScale(2, RoundingMode.HALF_UP);

            BigDecimal difference = calculatedItemTotal
                    .subtract(providedItemTotal)
                    .abs();

            if (difference.compareTo(new BigDecimal("0.01")) > 0) {
                throw new IllegalArgumentException(
                        "Item total does not match quantity × unit price for: "
                                + item.getName()
                );
            }

            item.setTotal(calculatedItemTotal);
            item.setReceipt(receipt);

            calculatedSubtotal = calculatedSubtotal
                    .add(calculatedItemTotal);
        }

        calculatedSubtotal = calculatedSubtotal
                .setScale(2, RoundingMode.HALF_UP);

        receipt.setSubtotal(calculatedSubtotal);

        BigDecimal discount = receipt.getDiscount() != null
                ? receipt.getDiscount()
                : BigDecimal.ZERO;

        BigDecimal tax = receipt.getTax() != null
                ? receipt.getTax()
                : BigDecimal.ZERO;

        BigDecimal fee = receipt.getFee() != null
                ? receipt.getFee()
                : BigDecimal.ZERO;

        BigDecimal calculatedTotal = calculatedSubtotal
                .subtract(discount)
                .add(tax)
                .add(fee)
                .setScale(2, RoundingMode.HALF_UP);

        receipt.setDiscount(discount.setScale(2, RoundingMode.HALF_UP));
        receipt.setTax(tax.setScale(2, RoundingMode.HALF_UP));
        receipt.setFee(fee.setScale(2, RoundingMode.HALF_UP));

        BigDecimal providedTotal = receipt.getTotal()
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal totalDifference = calculatedTotal
                .subtract(providedTotal)
                .abs();

        if (totalDifference.compareTo(new BigDecimal("0.01")) > 0) {
            throw new IllegalArgumentException(
                    "Receipt total does not match subtotal - discount + tax + fee"
            );
        }

        receipt.setTotal(calculatedTotal);

        return receiptRepository.save(receipt);
    }
}