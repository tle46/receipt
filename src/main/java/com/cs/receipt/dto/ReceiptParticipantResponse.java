package com.cs.receipt.dto;

import com.cs.receipt.model.ReceiptParticipant;
import java.math.BigDecimal;

public record ReceiptParticipantResponse(Long id, Long userId, BigDecimal finalOwedAmount) {
    public static ReceiptParticipantResponse from(ReceiptParticipant participant) {
        return new ReceiptParticipantResponse(participant.getId(), participant.getUser().getId(),
                participant.getFinalOwedAmount());
    }
}
