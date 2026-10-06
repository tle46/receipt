package com.cs.receipt.model;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "receipt_adjustment_allocations", uniqueConstraints =
        @UniqueConstraint(name = "uk_receipt_adjustment_participant",
                columnNames = {"receipt_id", "adjustment_type", "participant_id"}))
public class ReceiptAdjustmentAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id", nullable = false)
    private Receipt receipt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "participant_id", nullable = false)
    private ReceiptParticipant participant;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", nullable = false, length = 10)
    private ReceiptAdjustmentType adjustmentType;

    @Column(precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    public Long getId() { return id; }
    public Receipt getReceipt() { return receipt; }
    public void setReceipt(Receipt receipt) { this.receipt = receipt; }
    public ReceiptParticipant getParticipant() { return participant; }
    public void setParticipant(ReceiptParticipant participant) { this.participant = participant; }
    public ReceiptAdjustmentType getAdjustmentType() { return adjustmentType; }
    public void setAdjustmentType(ReceiptAdjustmentType adjustmentType) { this.adjustmentType = adjustmentType; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
