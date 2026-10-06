package com.cs.receipt.repository;

import com.cs.receipt.model.Receipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReceiptRepository extends JpaRepository<Receipt, Long> {

    List<Receipt> findByOwnerId(Long ownerId);

    List<Receipt> findDistinctByOwnerIdOrParticipantsUserId(Long ownerId, Long participantUserId);
}

