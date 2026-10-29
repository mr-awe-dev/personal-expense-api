package com.fastwork.expense.export;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ExportService {

    private static final String ORDER_CREATED =
            " where user_id = :userId order by created_at asc, id asc";
    private static final String ORDER_CLOSED =
            " where user_id = :userId order by closed_at asc, id asc";

    private static final RowMapper<BackupFile.BackupCategory> CATEGORY_MAPPER =
            ExportService::mapCategory;
    private static final RowMapper<BackupFile.BackupTransaction> TRANSACTION_MAPPER =
            ExportService::mapTransaction;
    private static final RowMapper<BackupFile.BackupBudget> BUDGET_MAPPER =
            ExportService::mapBudget;
    private static final RowMapper<BackupFile.BackupClosing> CLOSING_MAPPER =
            ExportService::mapClosing;

    private final JdbcClient jdbcClient;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public BackupFile export(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow();
        BackupFile.BackupProfile profile =
                new BackupFile.BackupProfile(
                        user.getDisplayName(), user.getTimezone(), user.getDailyLimit());
        return new BackupFile(
                1,
                java.time.Instant.now(),
                profile,
                jdbcClient
                        .sql(
                                """
                                select id, type, name, archived, created_at, updated_at
                                from categories"""
                                        + ORDER_CREATED)
                        .param("userId", userId)
                        .query(CATEGORY_MAPPER)
                        .list(),
                jdbcClient
                        .sql(
                                """
                                select id, date, type, category_id, description, amount, closing_id,
                                       created_at, updated_at
                                from transactions"""
                                        + ORDER_CREATED)
                        .param("userId", userId)
                        .query(TRANSACTION_MAPPER)
                        .list(),
                jdbcClient
                        .sql(
                                """
                                select id, category_id, period, amount, description, created_at, updated_at
                                from budgets"""
                                        + ORDER_CREATED)
                        .param("userId", userId)
                        .query(BUDGET_MAPPER)
                        .list(),
                jdbcClient
                        .sql(
                                """
                                select id, period_start, period_end, total_income, total_expense, net,
                                       tx_count, file_name, closed_at
                                from closings"""
                                        + ORDER_CLOSED)
                        .param("userId", userId)
                        .query(CLOSING_MAPPER)
                        .list());
    }

    private static BackupFile.BackupCategory mapCategory(ResultSet rs, int rowNum)
            throws SQLException {
        return new BackupFile.BackupCategory(
                rs.getObject("id", UUID.class),
                rs.getString("type"),
                rs.getString("name"),
                rs.getBoolean("archived"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static BackupFile.BackupTransaction mapTransaction(ResultSet rs, int rowNum)
            throws SQLException {
        return new BackupFile.BackupTransaction(
                rs.getObject("id", UUID.class),
                rs.getDate("date").toLocalDate(),
                rs.getString("type"),
                rs.getObject("category_id", UUID.class),
                rs.getString("description"),
                rs.getLong("amount"),
                rs.getObject("closing_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static BackupFile.BackupBudget mapBudget(ResultSet rs, int rowNum) throws SQLException {
        return new BackupFile.BackupBudget(
                rs.getObject("id", UUID.class),
                rs.getObject("category_id", UUID.class),
                rs.getString("period"),
                rs.getLong("amount"),
                rs.getString("description"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static BackupFile.BackupClosing mapClosing(ResultSet rs, int rowNum)
            throws SQLException {
        return new BackupFile.BackupClosing(
                rs.getObject("id", UUID.class),
                rs.getDate("period_start").toLocalDate(),
                rs.getDate("period_end").toLocalDate(),
                rs.getLong("total_income"),
                rs.getLong("total_expense"),
                rs.getLong("net"),
                rs.getInt("tx_count"),
                rs.getString("file_name"),
                rs.getTimestamp("closed_at").toInstant());
    }
}
