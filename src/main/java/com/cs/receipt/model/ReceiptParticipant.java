package com.cs.receipt.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "receipt_participants", uniqueConstraints =
        @UniqueConstraint(name = "uk_receipt_participant", columnNames = {"receipt_id", "user_id"}))
public class ReceiptParticipant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "receipt_id", nullable = false)
    private Receipt receipt;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(precision = 19, scale = 2, nullable = false)
    private BigDecimal finalOwedAmount = BigDecimal.ZERO;

    public Long getId() { return id; }
    public Receipt getReceipt() { return receipt; }
    public void setReceipt(Receipt receipt) { this.receipt = receipt; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public BigDecimal getFinalOwedAmount() { return finalOwedAmount; }
    public void setFinalOwedAmount(BigDecimal finalOwedAmount) { this.finalOwedAmount = finalOwedAmount; }
}
