package com.fastwork.expense.budget;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fastwork.expense.category.Category;
import com.fastwork.expense.category.CategoryRepository;
import com.fastwork.expense.common.ApiException;
import com.fastwork.expense.common.TxType;
import com.fastwork.expense.common.UuidV7;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BudgetService {

    private static final String LIST_SQL =
            """
            SELECT b.category_id, c.name as category_name, c.archived as category_archived,
                   b.period, b.amount, b.description, b.created_at, b.updated_at
            FROM budgets b
            JOIN categories c ON c.id = b.category_id
            WHERE b.user_id = :userId
            ORDER BY c.name ASC, b.category_id ASC
            """;

    private static final RowMapper<BudgetResponse> ROW_MAPPER = BudgetService::mapRow;

    private final JdbcClient jdbcClient;
    private final BudgetRepository budgetRepository;
    private final CategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public List<BudgetResponse> list(UUID userId) {
        return jdbcClient
                .sql(LIST_SQL)
                .paramSource(new MapSqlParameterSource().addValue("userId", userId))
                .query(ROW_MAPPER)
                .list();
    }

    /** Upsert idempotent: 1 kategori = 1 budget; kategori harus milik user dan bertipe expense. */
    @Transactional
    public BudgetResponse upsert(UUID userId, UUID categoryId, UpsertBudgetRequest request) {
        Category category =
                categoryRepository
                        .findByIdAndUserId(categoryId, userId)
                        .orElseThrow(() -> ApiException.notFound("Kategori tidak ditemukan"));
        if (category.getType() != TxType.EXPENSE) {
            throw ApiException.unprocessable("Budget hanya untuk kategori pengeluaran");
        }
        Budget budget = budgetRepository.findByCategoryIdAndUserId(categoryId, userId).orElse(null);
        String description = normalizeDescription(request.description());
        if (budget == null) {
            budget =
                    new Budget(
                            UuidV7.random(),
                            userId,
                            categoryId,
                            request.period(),
                            request.amount(),
                            description);
        } else {
            budget.setPeriod(request.period());
            budget.setAmount(request.amount());
            budget.setDescription(description);
        }
        budgetRepository.saveAndFlush(budget);
        return new BudgetResponse(
                categoryId,
                category.getName(),
                Boolean.TRUE.equals(category.getArchived()),
                budget.getPeriod(),
                budget.getAmount(),
                budget.getDescription(),
                budget.getCreatedAt(),
                budget.getUpdatedAt());
    }

    @Transactional
    public void delete(UUID userId, UUID categoryId) {
        Budget budget =
                budgetRepository
                        .findByCategoryIdAndUserId(categoryId, userId)
                        .orElseThrow(() -> ApiException.notFound("Budget tidak ditemukan"));
        budgetRepository.delete(budget);
    }

    private static BudgetResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new BudgetResponse(
                rs.getObject("category_id", UUID.class),
                rs.getString("category_name"),
                rs.getBoolean("category_archived"),
                BudgetPeriod.valueOf(rs.getString("period").toUpperCase(Locale.ROOT)),
                rs.getLong("amount"),
                rs.getString("description"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static String normalizeDescription(String description) {
        if (description == null) {
            return "";
        }
        return description.strip();
    }
}
