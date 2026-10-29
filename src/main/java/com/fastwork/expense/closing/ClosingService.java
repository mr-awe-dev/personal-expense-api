package com.fastwork.expense.closing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fastwork.expense.budget.BudgetPeriod;
import com.fastwork.expense.common.ApiException;
import com.fastwork.expense.common.UuidV7;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ClosingService {

    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String OPEN_BOUNDS_SQL =
            "select min(date) as first_date, max(date) as last_date"
                    + " from transactions"
                    + " where user_id = :userId and closing_id is null";

    private static final String OPEN_TOTALS_SQL =
            "select count(*) as tx_count,"
                    + " coalesce(sum(case when type = 'income' then amount end), 0) as income,"
                    + " coalesce(sum(case when type = 'expense' then amount end), 0) as expense"
                    + " from transactions"
                    + " where user_id = :userId and closing_id is null"
                    + " and date >= :from and date <= :to";

    private static final String INSERT_SQL =
            "insert into closings (id, user_id, period_start, period_end, total_income,"
                    + " total_expense, net, tx_count, file_name)"
                    + " values (:id, :userId, :from, :to, :income, :expense, :income - :expense,"
                    + " :txCount, :fileName)"
                    + " returning id, closed_at";

    private static final String ATTACH_SQL =
            "update transactions set closing_id = :closingId"
                    + " where user_id = :userId and closing_id is null"
                    + " and date >= :from and date <= :to";

    private static final String LIST_SQL =
            "select id, period_start, period_end, total_income, total_expense, net, tx_count,"
                    + " file_name, closed_at"
                    + " from closings"
                    + " where user_id = :userId"
                    + " order by closed_at desc, id desc";

    private static final String BY_ID_SQL =
            "select id, period_start, period_end, total_income, total_expense, net, tx_count,"
                    + " file_name, closed_at"
                    + " from closings"
                    + " where id = :id and user_id = :userId";

    private static final String DETACH_SQL =
            "update transactions set closing_id = null where closing_id = :closingId";

    private static final String DELETE_LOG_SQL =
            "delete from closings where id = :id and user_id = :userId";

    private static final String DELETE_DATA_SQL =
            "delete from transactions where closing_id = :closingId";

    private static final String EXPORT_ROWS_SQL =
            "select t.id, t.date, t.type, t.category_id, c.name as category_name, t.description,"
                    + " t.amount"
                    + " from transactions t"
                    + " left join categories c on c.id = t.category_id"
                    + " where t.user_id = :userId and t.closing_id = :closingId"
                    + " order by t.date asc, t.created_at asc, t.id asc";

    private static final String BUDGETS_SQL =
            "select b.category_id, b.period, b.amount"
                    + " from budgets b"
                    + " where b.user_id = :userId"
                    + " order by b.category_id asc";

    private final JdbcClient jdbcClient;

    @Transactional(readOnly = true)
    public ClosingPreview preview(
            UUID userId,
            String timezone,
            @Nullable LocalDate requestedFrom,
            @Nullable LocalDate requestedTo) {
        if (requestedFrom == null || requestedTo == null) {
            Bounds bounds = openBounds(userId);
            if (requestedFrom == null) {
                requestedFrom = bounds.first() != null ? bounds.first() : localToday(timezone);
            }
            if (requestedTo == null) {
                requestedTo = bounds.last() != null ? bounds.last() : localToday(timezone);
            }
        }
        Window window = validate(new Window(requestedFrom, requestedTo));
        Totals totals = openTotals(userId, window);
        return new ClosingPreview(
                window.from(),
                window.to(),
                totals.count(),
                totals.income(),
                totals.expense(),
                totals.net());
    }

    @Transactional
    public ClosingRecord close(UUID userId, ClosePeriodRequest request) {
        Window window = validate(new Window(request.from(), request.to()));
        Totals totals = openTotals(userId, window);
        if (totals.count() == 0) {
            throw ApiException.unprocessable("Tidak ada transaksi untuk rentang ini");
        }
        UUID id = UuidV7.random();
        String fileName = fileName(window);
        Saved saved =
                jdbcClient
                        .sql(INSERT_SQL)
                        .param("id", id)
                        .param("userId", userId)
                        .param("from", window.from())
                        .param("to", window.to())
                        .param("income", totals.income())
                        .param("expense", totals.expense())
                        .param("txCount", totals.count())
                        .param("fileName", fileName)
                        .query((rs, rowNum) -> mapSaved(rs))
                        .list()
                        .getFirst();
        jdbcClient
                .sql(ATTACH_SQL)
                .param("closingId", id)
                .param("userId", userId)
                .param("from", window.from())
                .param("to", window.to())
                .update();
        return new ClosingRecord(
                saved.id(),
                window.from(),
                window.to(),
                totals.income(),
                totals.expense(),
                totals.net(),
                (int) totals.count(),
                fileName,
                saved.closedAt());
    }

    @Transactional(readOnly = true)
    public List<ClosingRecord> list(UUID userId) {
        return jdbcClient
                .sql(LIST_SQL)
                .param("userId", userId)
                .query((rs, rowNum) -> mapRow(rs))
                .list();
    }

    @Transactional
    public void reopen(UUID userId, UUID id) {
        require(userId, id);
        jdbcClient.sql(DETACH_SQL).param("closingId", id).update();
        jdbcClient.sql(DELETE_LOG_SQL).param("id", id).param("userId", userId).update();
    }

    @Transactional
    public void deleteData(UUID userId, UUID id, String confirm) {
        require(userId, id);
        if (!"HAPUS".equals(confirm)) {
            throw ApiException.badRequest("CONFIRM_MISMATCH", "Ketik HAPUS untuk konfirmasi");
        }
        jdbcClient.sql(DELETE_DATA_SQL).param("closingId", id).update();
    }

    @Transactional(readOnly = true)
    public ClosingExport export(UUID userId, UUID id) {
        ClosingRecord record = require(userId, id);
        Window window = new Window(record.periodStart(), record.periodEnd());
        List<ClosingExport.ExportRow> rows =
                jdbcClient
                        .sql(EXPORT_ROWS_SQL)
                        .param("userId", userId)
                        .param("closingId", id)
                        .query((rs, rowNum) -> mapExportRow(rs))
                        .list();
        Map<UUID, BudgetPeriod> periodByCategory = new HashMap<>();
        Map<UUID, Long> amountByCategory = new HashMap<>();
        jdbcClient
                .sql(BUDGETS_SQL)
                .param("userId", userId)
                .query(
                        (rs, rowNum) -> {
                            UUID categoryId = rs.getObject("category_id", UUID.class);
                            periodByCategory.put(
                                    categoryId,
                                    BudgetPeriod.valueOf(
                                            rs.getString("period").toUpperCase(Locale.ROOT)));
                            amountByCategory.put(categoryId, rs.getLong("amount"));
                            return true;
                        })
                .list();
        LinkedHashMap<GroupKey, Long> sums = new LinkedHashMap<>();
        for (ClosingExport.ExportRow row : rows) {
            sums.merge(
                    new GroupKey(row.type(), row.categoryId(), row.categoryName()),
                    row.amount(),
                    Long::sum);
        }
        long days = daysBetween(window);
        List<ClosingExport.CategorySubtotal> subtotals = new ArrayList<>(sums.size());
        for (Map.Entry<GroupKey, Long> entry : sums.entrySet()) {
            GroupKey key = entry.getKey();
            Long budgetAmount = null;
            Long difference = null;
            if ("EXPENSE".equals(key.type())
                    && key.categoryId() != null
                    && periodByCategory.containsKey(key.categoryId())) {
                long budget =
                        scale(
                                periodByCategory.get(key.categoryId()),
                                amountByCategory.get(key.categoryId()),
                                days);
                budgetAmount = budget;
                difference = entry.getValue() - budget;
            }
            subtotals.add(
                    new ClosingExport.CategorySubtotal(
                            key.categoryId(),
                            key.categoryName(),
                            key.type(),
                            entry.getValue(),
                            budgetAmount,
                            difference));
        }
        subtotals.sort(
                Comparator.comparing(
                                (ClosingExport.CategorySubtotal s) ->
                                        "INCOME".equals(s.type()) ? 1 : 0)
                        .thenComparing(s -> s.categoryName() == null ? "" : s.categoryName())
                        .thenComparing(
                                s -> s.categoryId() == null ? "" : s.categoryId().toString()));
        return new ClosingExport(record, rows, subtotals);
    }

    private Bounds openBounds(UUID userId) {
        return jdbcClient
                .sql(OPEN_BOUNDS_SQL)
                .param("userId", userId)
                .query(
                        (rs, rowNum) ->
                                new Bounds(
                                        rs.getObject("first_date", LocalDate.class),
                                        rs.getObject("last_date", LocalDate.class)))
                .list()
                .getFirst();
    }

    private Totals openTotals(UUID userId, Window window) {
        return jdbcClient
                .sql(OPEN_TOTALS_SQL)
                .param("userId", userId)
                .param("from", window.from())
                .param("to", window.to())
                .query(
                        (rs, rowNum) ->
                                new Totals(
                                        rs.getLong("tx_count"),
                                        rs.getLong("income"),
                                        rs.getLong("expense")))
                .list()
                .getFirst();
    }

    private ClosingRecord require(UUID userId, UUID id) {
        List<ClosingRecord> records =
                jdbcClient
                        .sql(BY_ID_SQL)
                        .param("id", id)
                        .param("userId", userId)
                        .query((rs, rowNum) -> mapRow(rs))
                        .list();
        if (records.isEmpty()) {
            throw ApiException.notFound("Arsip tidak ditemukan");
        }
        return records.getFirst();
    }

    private static Window validate(Window window) {
        if (window.from().isAfter(window.to())) {
            throw ApiException.badRequest("INVALID_RANGE", "Rentang tanggal tidak valid");
        }
        return window;
    }

    private static String fileName(Window window) {
        return "closing_"
                + COMPACT_DATE.format(window.from())
                + "-"
                + COMPACT_DATE.format(window.to())
                + ".xlsx";
    }

    private static LocalDate localToday(String timezone) {
        try {
            return LocalDate.now(ZoneId.of(timezone));
        } catch (DateTimeException invalid) {
            return LocalDate.now(ZoneOffset.UTC);
        }
    }

    private static long scale(BudgetPeriod period, long amount, long days) {
        if (period == BudgetPeriod.MONTHLY) {
            return amount;
        }
        return amount * days;
    }

    private static long daysBetween(Window window) {
        return ChronoUnit.DAYS.between(window.from(), window.to()) + 1;
    }

    private static ClosingRecord mapRow(ResultSet rs) throws SQLException {
        return new ClosingRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("period_start", LocalDate.class),
                rs.getObject("period_end", LocalDate.class),
                rs.getLong("total_income"),
                rs.getLong("total_expense"),
                rs.getLong("net"),
                rs.getInt("tx_count"),
                rs.getString("file_name"),
                rs.getTimestamp("closed_at").toInstant());
    }

    private static Saved mapSaved(ResultSet rs) throws SQLException {
        return new Saved(rs.getObject("id", UUID.class), rs.getTimestamp("closed_at").toInstant());
    }

    private static ClosingExport.ExportRow mapExportRow(ResultSet rs) throws SQLException {
        return new ClosingExport.ExportRow(
                rs.getObject("id", UUID.class),
                rs.getObject("date", LocalDate.class),
                rs.getString("type").toUpperCase(Locale.ROOT),
                rs.getObject("category_id", UUID.class),
                rs.getString("category_name"),
                rs.getString("description"),
                rs.getLong("amount"));
    }

    private record Window(LocalDate from, LocalDate to) {}

    private record Bounds(@Nullable LocalDate first, @Nullable LocalDate last) {}

    private record Totals(long count, long income, long expense) {
        long net() {
            return income - expense;
        }
    }

    private record Saved(UUID id, Instant closedAt) {}

    private record GroupKey(String type, UUID categoryId, String categoryName) {}
}
