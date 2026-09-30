package com.cs.receipt.controller;

import com.cs.receipt.dto.CreateReceiptItemRequest;
import com.cs.receipt.dto.CreateReceiptRequest;
import com.cs.receipt.dto.ReceiptResponse;
import com.cs.receipt.dto.CreateReceiptParticipantRequest;
import com.cs.receipt.dto.CreateReceiptItemAllocationRequest;
import com.cs.receipt.dto.ReceiptParticipantResponse;
import com.cs.receipt.dto.ReceiptItemAllocationResponse;
import com.cs.receipt.dto.ReceiptSplitResponse;
import com.cs.receipt.dto.UpdateReceiptItemRequest;
import com.cs.receipt.dto.UpdateReceiptItemAllocationRequest;
import com.cs.receipt.model.Receipt;
import com.cs.receipt.model.ReceiptItem;
import com.cs.receipt.service.ReceiptService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.List;

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

        receipt.setDiscount(request.getDiscount());
        receipt.setTax(request.getTax());
        receipt.setFee(request.getFee());
        receipt.setTip(request.getTip());
        receipt.setTotal(request.getTotal());
        receipt.setCurrency(request.getCurrency());

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

    @GetMapping("/{receiptId}")
    public ReceiptResponse getReceipt(@PathVariable Long receiptId, @RequestParam Long userId) {
        return receiptService.getReceipt(receiptId, userId);
    }

    @GetMapping
    public List<ReceiptResponse> listReceipts(@RequestParam Long userId) {
        return receiptService.listReceipts(userId);
    }

    @PostMapping("/{receiptId}/participants")
    @ResponseStatus(HttpStatus.CREATED)
    public ReceiptParticipantResponse addParticipant(@PathVariable Long receiptId,
                                                     @RequestParam Long userId,
                                                     @Valid @RequestBody CreateReceiptParticipantRequest request) {
        return ReceiptParticipantResponse.from(receiptService.addParticipant(
                receiptId, userId, request.getUserId()));
    }

    @PostMapping("/{receiptId}/items/{itemId}/allocations")
    @ResponseStatus(HttpStatus.CREATED)
    public ReceiptItemAllocationResponse addItemAllocation(@PathVariable Long receiptId,
                                                            @PathVariable Long itemId,
                                                            @RequestParam Long userId,
                                                            @Valid @RequestBody CreateReceiptItemAllocationRequest request) {
        return ReceiptItemAllocationResponse.from(receiptService.addItemAllocation(receiptId, itemId,
                userId, request.getParticipantId(), request.getAllocationType(), request.getInputValue()));
    }

    @PutMapping("/{receiptId}/items/{itemId}")
    public ReceiptResponse.ReceiptItemResponse updateReceiptItem(@PathVariable Long receiptId,
                                                                  @PathVariable Long itemId,
                                                                  @RequestParam Long userId,
                                                                  @Valid @RequestBody UpdateReceiptItemRequest request) {
        ReceiptItem item = receiptService.updateReceiptItem(receiptId, itemId, userId, request.getName(),
                request.getQuantity(), request.getUnitPrice());
        return new ReceiptResponse.ReceiptItemResponse(item.getId(), item.getName(), item.getQuantity(),
                item.getUnitPrice(), item.getTotal(), item.getAllocations().stream()
                .map(ReceiptItemAllocationResponse::from).toList());
    }

    @DeleteMapping("/{receiptId}/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteReceiptItem(@PathVariable Long receiptId, @PathVariable Long itemId,
                                  @RequestParam Long userId) {
        receiptService.deleteReceiptItem(receiptId, itemId, userId);
    }

    @PutMapping("/{receiptId}/items/{itemId}/allocations/{allocationId}")
    public ReceiptItemAllocationResponse updateItemAllocation(@PathVariable Long receiptId,
                                                               @PathVariable Long itemId,
                                                               @PathVariable Long allocationId,
                                                               @RequestParam Long userId,
                                                               @Valid @RequestBody UpdateReceiptItemAllocationRequest request) {
        return ReceiptItemAllocationResponse.from(receiptService.updateItemAllocation(receiptId, itemId,
                allocationId, userId, request.getAllocationType(), request.getInputValue()));
    }

    @DeleteMapping("/{receiptId}/items/{itemId}/allocations/{allocationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItemAllocation(@PathVariable Long receiptId, @PathVariable Long itemId,
                                     @PathVariable Long allocationId, @RequestParam Long userId) {
        receiptService.deleteItemAllocation(receiptId, itemId, allocationId, userId);
    }

    @DeleteMapping("/{receiptId}/participants/{participantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeParticipant(@PathVariable Long receiptId, @PathVariable Long participantId,
                                  @RequestParam Long userId) {
        receiptService.removeParticipant(receiptId, participantId, userId);
    }

    @PostMapping("/{receiptId}/calculate")
    public ReceiptSplitResponse calculateSplit(@PathVariable Long receiptId, @RequestParam Long userId) {
        return ReceiptSplitResponse.from(receiptService.calculateSplit(receiptId, userId));
    }

    @PostMapping("/{receiptId}/finalize")
    public ReceiptSplitResponse finalizeReceipt(@PathVariable Long receiptId, @RequestParam Long userId) {
        return ReceiptSplitResponse.from(receiptService.finalizeReceipt(receiptId, userId));
    }

    @PostMapping("/{receiptId}/reopen")
    public ReceiptResponse reopenReceipt(@PathVariable Long receiptId, @RequestParam Long userId) {
        return ReceiptResponse.fromReceipt(receiptService.reopenReceipt(receiptId, userId));
    }

    @PostMapping("/{receiptId}/settle")
    public ReceiptResponse settleReceipt(@PathVariable Long receiptId, @RequestParam Long userId) {
        return ReceiptResponse.fromReceipt(receiptService.settleReceipt(receiptId, userId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleIllegalArgumentException(
            IllegalArgumentException exception) {

        return Map.of("error", exception.getMessage());
    }
}
