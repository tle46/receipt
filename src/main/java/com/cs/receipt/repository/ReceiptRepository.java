package com.cs.receipt.repository;

import com.cs.receipt.model.Receipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReceiptRepository extends JpaRepository<Receipt, Long> {

    @org.springframework.data.jpa.repository.Query("""
            select distinct r from Receipt r left join r.participants p
            where r.owner.id = :userId
               or (p.user.id = :userId and r.status in :sharedStatuses)
            """)
    List<Receipt> findVisibleToUser(
            @org.springframework.data.repository.query.Param("userId") Long userId,
            @org.springframework.data.repository.query.Param("sharedStatuses") List<com.cs.receipt.model.ReceiptStatus> sharedStatuses);
}

