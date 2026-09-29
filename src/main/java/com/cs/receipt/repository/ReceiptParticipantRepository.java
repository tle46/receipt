package com.cs.receipt.repository;

import com.cs.receipt.model.ReceiptParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceiptParticipantRepository extends JpaRepository<ReceiptParticipant, Long> {
}
