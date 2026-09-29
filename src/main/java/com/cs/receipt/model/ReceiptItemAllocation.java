package com.cs.receipt.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "receipt_item_allocations", uniqueConstraints =
        @UniqueConstraint(name = "uk_item_participant", columnNames = {"receipt_item_id", "participant_id"}))
public class ReceiptItemAllocation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "receipt_item_id", nullable = false)
    private ReceiptItem receiptItem;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "participant_id", nullable = false)
    private ReceiptParticipant participant;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private AllocationType allocationType;

    @Column(precision = 19, scale = 4, nullable = false)
    private BigDecimal inputValue;

    @Column(precision = 19, scale = 2, nullable = false)
    private BigDecimal finalAmount = BigDecimal.ZERO;

    public Long getId() { return id; }
    public ReceiptItem getReceiptItem() { return receiptItem; }
    public void setReceiptItem(ReceiptItem receiptItem) { this.receiptItem = receiptItem; }
    public ReceiptParticipant getParticipant() { return participant; }
    public void setParticipant(ReceiptParticipant participant) { this.participant = participant; }
    public AllocationType getAllocationType() { return allocationType; }
    public void setAllocationType(AllocationType allocationType) { this.allocationType = allocationType; }
    public BigDecimal getInputValue() { return inputValue; }
    public void setInputValue(BigDecimal inputValue) { this.inputValue = inputValue; }
    public BigDecimal getFinalAmount() { return finalAmount; }
    public void setFinalAmount(BigDecimal finalAmount) { this.finalAmount = finalAmount; }
}
