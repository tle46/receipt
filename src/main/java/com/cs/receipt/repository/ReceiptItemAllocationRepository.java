package com.cs.receipt.repository;

import com.cs.receipt.model.ReceiptItemAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceiptItemAllocationRepository extends JpaRepository<ReceiptItemAllocation, Long> {
}
