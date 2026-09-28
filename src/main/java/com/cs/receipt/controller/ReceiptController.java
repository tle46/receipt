package com.cs.receipt.controller;

import com.cs.receipt.dto.CreateReceiptItemRequest;
import com.cs.receipt.dto.CreateReceiptRequest;
import com.cs.receipt.dto.ReceiptResponse;
import com.cs.receipt.model.Receipt;
import com.cs.receipt.model.ReceiptItem;
import com.cs.receipt.service.ReceiptService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/receipts")
public class ReceiptController {

    private final ReceiptService receiptService;

    public ReceiptController(ReceiptService receiptService) {
        this.receiptService = receiptService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReceiptResponse createReceipt(
            @RequestParam Long userId,
            @Valid @RequestBody CreateReceiptRequest request) {

        Receipt receipt = new Receipt();

        receipt.setMerchantName(request.getMerchantName());
        receipt.setPurchaseDate(request.getPurchaseDate());

        receipt.setSubtotal(request.getSubtotal());
        receipt.setDiscount(request.getDiscount());
        receipt.setTax(request.getTax());
        receipt.setFee(request.getFee());
        receipt.setTotal(request.getTotal());

        for (CreateReceiptItemRequest itemRequest : request.getItems()) {

            ReceiptItem item = new ReceiptItem();

            item.setName(itemRequest.getName());
            item.setQuantity(itemRequest.getQuantity());
            item.setUnitPrice(itemRequest.getUnitPrice());
            item.setTotal(itemRequest.getTotal());

            receipt.getItems().add(item);
        }

        Receipt savedReceipt =
                receiptService.createReceipt(userId, receipt);

        return ReceiptResponse.fromReceipt(savedReceipt);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleIllegalArgumentException(
            IllegalArgumentException exception) {

        return Map.of("error", exception.getMessage());
    }
}
