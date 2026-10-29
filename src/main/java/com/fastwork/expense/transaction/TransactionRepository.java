package com.fastwork.expense.transaction;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    boolean existsByCategoryIdAndUserId(UUID categoryId, UUID userId);

    Optional<Transaction> findByIdAndUserId(UUID id, UUID userId);
}
