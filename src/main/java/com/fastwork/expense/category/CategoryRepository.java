package com.fastwork.expense.category;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.fastwork.expense.common.TxType;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    List<Category> findByUserIdOrderByCreatedAtAscIdAsc(UUID userId);

    Optional<Category> findByIdAndUserId(UUID id, UUID userId);

    Optional<Category> findByUserIdAndTypeAndNameKey(UUID userId, TxType type, String nameKey);
}
