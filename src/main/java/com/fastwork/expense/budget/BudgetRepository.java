package com.fastwork.expense.budget;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BudgetRepository extends JpaRepository<Budget, UUID> {

    Optional<Budget> findByCategoryIdAndUserId(UUID categoryId, UUID userId);

    List<Budget> findByUserIdOrderByCreatedAtAscIdAsc(UUID userId);
}
