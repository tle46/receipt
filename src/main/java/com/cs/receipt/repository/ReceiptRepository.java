package com.cs.receipt.repository;

import com.cs.receipt.model.Receipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReceiptRepository extends JpaRepository<Receipt, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from Receipt r where r.id = :id")
    java.util.Optional<Receipt> findLockedById(@org.springframework.data.repository.query.Param("id") Long id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from Receipt r where r.owner.id = :ownerId and r.status = com.cs.receipt.model.ReceiptStatus.FINALIZED order by r.id")
    List<Receipt> findLockedOutstandingByOwner(@org.springframework.data.repository.query.Param("ownerId") Long ownerId);

    @org.springframework.data.jpa.repository.Query("""
            select distinct r from Receipt r left join r.participants p
            where r.owner.id = :userId
               or (p.user.id = :userId and r.status in :sharedStatuses)
            """)
    List<Receipt> findVisibleToUser(
            @org.springframework.data.repository.query.Param("userId") Long userId,
            @org.springframework.data.repository.query.Param("sharedStatuses") List<com.cs.receipt.model.ReceiptStatus> sharedStatuses);
}

