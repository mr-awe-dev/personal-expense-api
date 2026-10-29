package com.fastwork.expense.summary;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fastwork.expense.budget.BudgetPeriod;
import com.fastwork.expense.common.ApiException;
import com.fastwork.expense.common.TxType;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SummaryService {

    static final long DEFAULT_ROW_LIMIT = 2000L;
    static final long MAX_ROW_LIMIT = 5000L;

    /** Totals per jenis; dihitung dari transaksi berjalan dalam rentang (inklusif). */
    private static final String TYPE_TOTALS_SQL =
            """
            SELECT t.type, coalesce(sum(t.amount), 0) as total
            FROM transactions t
            WHERE t.user_id = :userId and t.closing_id is null
              and t.date >= :from and t.date <= :to
            GROUP BY t.type
            """;

    /**
     * Per kategori/sumber (income selalu berkategori; expense boleh tanpa kategori), terbesar dulu
     * lalu nama naik untuk urutan yang stabil.
     */
    private static final String BY_CATEGORY_SQL =
            """
            select t.type, t.category_id, c.name as category_name, c.archived as category_archived,
                   coalesce(sum(t.amount), 0) as amount
            from transactions t
            left join categories c on c.id = t.category_id
            where t.user_id = :userId and t.closing_id is null
              and t.date >= :from and t.date <= :to
            group by t.type, t.category_id, c.name, c.archived
            order by amount desc, category_name asc, t.category_id asc
            """;

    /** Budget (semua periode) + aktual per kategori dalam rentang, termasuk kategori arsip. */
    private static final String BUDGET_ACTUAL_SQL =
            """
            select b.category_id, c.name as category_name, c.archived as category_archived,
                   b.period as period, b.amount as budget_amount, coalesce(s.spent, 0) as spent
            from budgets b
            join categories c on c.id = b.category_id
            left join (
                select t.category_id, sum(t.amount) as spent
                from transactions t
                where t.user_id = :userId and t.closing_id is null
                  and t.date >= :from and t.date <= :to
                  and t.type = 'expense' and t.category_id is not null
                group by t.category_id
            ) s on s.category_id = b.category_id
            where b.user_id = :userId
            order by c.name asc, b.category_id asc
            """;

    private static final String TREND_SQL =
            """
            select t.date, coalesce(sum(t.amount), 0) as expense
            from transactions t
            where t.user_id = :userId and t.closing_id is null
              and t.date >= :from and t.date <= :to and t.type = 'expense'
            group by t.date
            order by t.date asc
            """;

    /** Subtotal penuh per kategori (tidak pernah terpotong); expense dulu, nama naik. */
    private static final String TABLE_SUBTOTALS_SQL =
            """
            select t.category_id, c.name as category_name, t.type, c.archived as category_archived,
                   coalesce(sum(t.amount), 0) as subtotal
            from transactions t
            left join categories c on c.id = t.category_id
            where t.user_id = :userId and t.closing_id is null
              and t.date >= :from and t.date <= :to
            group by t.category_id, c.name, t.type, c.archived
            order by (t.type = 'income') asc, c.name asc nulls first, t.category_id asc
            """;

    /** Detail baris (bisa terpotong oleh limit). */
    private static final String TABLE_ROWS_SQL =
            """
            select t.id, t.date, t.type, t.category_id, c.name as category_name,
                   t.description, t.amount
            from transactions t
            left join categories c on c.id = t.category_id
            where t.user_id = :userId and t.closing_id is null
              and t.date >= :from and t.date <= :to
            order by t.date desc, t.created_at desc, t.id desc
            limit :limit
            """;

    private static final String TOTAL_COUNT_SQL =
            """
            select count(*)
            from transactions t
            where t.user_id = :userId and t.closing_id is null
              and t.date >= :from and t.date <= :to
            """;

    private final JdbcClient jdbcClient;

    @Transactional(readOnly = true)
    public SummaryResponse summary(
            UUID userId,
            String timezone,
            @Nullable LocalDate requestedFrom,
            @Nullable LocalDate requestedTo) {
        Window window = window(timezone, requestedFrom, requestedTo);

        List<CategoryRow> categoryRows =
                jdbcClient
                        .sql(BY_CATEGORY_SQL)
                        .paramSource(source(userId, window))
                        .query((rs, rowNum) -> mapCategoryRow(rs))
                        .list();
        Map<CategoryRow, Long> shareFloors = new HashMap<>();
        Map<CategoryRow, Long> shareRemainders = new HashMap<>();
        largestRemainderParts(categoryRows, shareFloors, shareRemainders);

        long total = totalAmount(categoryRows);
        List<CategoryShare> incomeBySource = new ArrayList<>();
        List<CategoryShare> expenseByCategory = new ArrayList<>();
        long floorsSum = 0;
        for (CategoryRow row : categoryRows) {
            floorsSum += shareFloors.getOrDefault(row, 0L);
        }
        long missing = total > 0 ? 100 - floorsSum : 0;
        Map<CategoryRow, Integer> bonus = new HashMap<>();
        if (missing > 0) {
            List<CategoryRow> ranked = new ArrayList<>(categoryRows);
            ranked.sort((a, b) -> Long.compare(shareRemainders.get(b), shareRemainders.get(a)));
            for (int index = 0; index < Math.min(missing, ranked.size()); index++) {
                bonus.merge(ranked.get(index), 1, Integer::sum);
            }
        }
        for (CategoryRow row : categoryRows) {
            long share = total > 0 ? shareFloors.get(row) + bonus.getOrDefault(row, 0) : 0;
            CategoryShare value =
                    new CategoryShare(
                            row.categoryId(), row.name(), row.archived(), row.amount(), share);
            if (row.type() == TxType.INCOME) {
                incomeBySource.add(value);
            } else {
                expenseByCategory.add(value);
            }
        }

        Totals totals = typeTotals(userId, window);
        return new SummaryResponse(
                window.from(),
                window.to(),
                new SummaryKpi(
                        totals.income(),
                        totals.expense(),
                        totals.income() - totals.expense(),
                        savingsRate(totals)),
                incomeBySource,
                expenseByCategory,
                budgetActuals(userId, window),
                trend(userId, window));
    }

    @Transactional(readOnly = true)
    public SummaryTableResponse table(
            UUID userId,
            String timezone,
            @Nullable LocalDate requestedFrom,
            @Nullable LocalDate requestedTo,
            @Nullable Integer requestedLimit) {
        Window window = window(timezone, requestedFrom, requestedTo);
        long limit = requestedLimit == null ? DEFAULT_ROW_LIMIT : requestedLimit;
        limit = Math.max(1, Math.min(limit, MAX_ROW_LIMIT));

        List<SubtotalRow> subtotalRows =
                jdbcClient
                        .sql(TABLE_SUBTOTALS_SQL)
                        .paramSource(source(userId, window))
                        .query((rs, rowNum) -> mapSubtotalRow(rs))
                        .list();
        Map<String, List<TableRow>> rowsByGroupKey = indexRowsByGroup(rows(userId, window, limit));
        List<BudgetRow> budgets =
                jdbcClient
                        .sql(BUDGET_ACTUAL_SQL)
                        .paramSource(source(userId, window))
                        .query((rs, rowNum) -> mapBudgetRow(rs))
                        .list();

        List<TableGroup> groups = new ArrayList<>(subtotalRows.size());
        for (SubtotalRow row : subtotalRows) {
            Long budgetAmount = null;
            Long difference = null;
            if (row.type() == TxType.EXPENSE && row.categoryId() != null) {
                BudgetRow budget = budgetFor(budgets, row.categoryId());
                if (budget != null) {
                    budgetAmount = effectiveBudget(budget.period(), budget.amount(), window);
                    difference = row.subtotal() - budgetAmount;
                }
            }
            String groupKey = groupKeyOf(row.type(), row.categoryId());
            groups.add(
                    new TableGroup(
                            row.categoryId(),
                            row.name(),
                            row.type().name(),
                            rowsByGroupKey.getOrDefault(groupKey, List.of()),
                            row.subtotal(),
                            budgetAmount,
                            difference));
        }

        Totals totals = typeTotals(userId, window);
        boolean truncated =
                jdbcClient
                                .sql(TOTAL_COUNT_SQL)
                                .paramSource(source(userId, window))
                                .query((rs, rowNum) -> rs.getLong(1))
                                .list()
                                .getFirst()
                        > limit;

        return new SummaryTableResponse(
                window.from(),
                window.to(),
                groups,
                new TableTotals(
                        totals.income(), totals.expense(), totals.income() - totals.expense()),
                truncated);
    }

    private record Window(LocalDate from, LocalDate to) {}

    private record Totals(long income, long expense) {}

    /** Baris GROUP BY per kategori/sumber untuk pembagian persentase. */
    private record CategoryRow(
            TxType type, UUID categoryId, String name, boolean archived, long amount) {}

    /** Baris budget + aktual (sebelum diskalakan ke periode rentang). */
    private record BudgetRow(
            UUID categoryId,
            String name,
            boolean archived,
            BudgetPeriod period,
            long amount,
            long spent) {}

    /** Baris subtotal tabel per kategori/sumber. */
    private record SubtotalRow(
            UUID categoryId, String name, boolean archived, TxType type, long subtotal) {}

    private static Window window(
            String timezone, @Nullable LocalDate from, @Nullable LocalDate to) {
        LocalDate today = localToday(timezone);
        LocalDate resolvedFrom = from != null ? from : today.withDayOfMonth(1);
        LocalDate resolvedTo = to != null ? to : today;
        if (resolvedFrom.isAfter(resolvedTo)) {
            throw ApiException.badRequest("INVALID_RANGE", "Rentang tanggal tidak valid");
        }
        return new Window(resolvedFrom, resolvedTo);
    }

    /** "Hari ini" mengikuti zona waktu profil user (aturan §10 poin 9). */
    private static LocalDate localToday(String timezone) {
        try {
            return LocalDate.now(ZoneId.of(timezone));
        } catch (DateTimeException invalid) {
            return LocalDate.now(ZoneOffset.UTC);
        }
    }

    private static MapSqlParameterSource source(UUID userId, Window window) {
        return new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("from", window.from())
                .addValue("to", window.to());
    }

    private Totals typeTotals(UUID userId, Window window) {
        long income = 0;
        long expense = 0;
        List<TotalPart> totals =
                jdbcClient
                        .sql(TYPE_TOTALS_SQL)
                        .paramSource(source(userId, window))
                        .query(
                                (rs, rowNum) ->
                                        new TotalPart(
                                                typeOf(rs.getString("type")), rs.getLong("total")))
                        .list();
        for (TotalPart part : totals) {
            if (part.type() == TxType.INCOME) {
                income = part.total();
            } else {
                expense = part.total();
            }
        }
        return new Totals(income, expense);
    }

    private record TotalPart(TxType type, long total) {}

    /** Savings rate = net/income (persen); null bila income 0 (pembagian nol). */
    private static Long savingsRate(Totals totals) {
        if (totals.income() <= 0) {
            return null;
        }
        return Math.round((totals.income() - totals.expense()) * 100.0 / totals.income());
    }

    private static long totalAmount(List<CategoryRow> rows) {
        long total = 0;
        for (CategoryRow row : rows) {
            total += row.amount();
        }
        return total;
    }

    private static void largestRemainderParts(
            List<CategoryRow> rows,
            Map<CategoryRow, Long> floors,
            Map<CategoryRow, Long> remainders) {
        long total = totalAmount(rows);
        for (CategoryRow row : rows) {
            long scaled = row.amount() * 100;
            floors.put(row, total > 0 ? scaled / total : 0);
            remainders.put(row, total > 0 ? scaled % total : 0);
        }
    }

    private List<BudgetActualRow> budgetActuals(UUID userId, Window window) {
        List<BudgetRow> rows =
                jdbcClient
                        .sql(BUDGET_ACTUAL_SQL)
                        .paramSource(source(userId, window))
                        .query((rs, rowNum) -> mapBudgetRow(rs))
                        .list();
        List<BudgetActualRow> actuals = new ArrayList<>(rows.size());
        for (BudgetRow row : rows) {
            long budget = effectiveBudget(row.period(), row.amount(), window);
            actuals.add(
                    new BudgetActualRow(
                            row.categoryId(),
                            row.name(),
                            row.archived(),
                            row.period().name(),
                            budget,
                            row.spent(),
                            budget - row.spent()));
        }
        return actuals;
    }

    private List<TrendPoint> trend(UUID userId, Window window) {
        List<TrendPoint> raw =
                jdbcClient
                        .sql(TREND_SQL)
                        .paramSource(source(userId, window))
                        .query(
                                (rs, rowNum) ->
                                        new TrendPoint(
                                                rs.getObject("date", LocalDate.class),
                                                rs.getLong("expense")))
                        .list();
        Map<LocalDate, Long> sums = new HashMap<>();
        for (TrendPoint point : raw) {
            sums.put(point.date(), point.expense());
        }
        List<TrendPoint> trend = new ArrayList<>();
        long days = ChronoUnit.DAYS.between(window.from(), window.to()) + 1;
        for (long offset = 0; offset < days; offset++) {
            LocalDate date = window.from().plusDays(offset);
            trend.add(new TrendPoint(date, sums.getOrDefault(date, 0L)));
        }
        return trend;
    }

    private List<TableRow> rows(UUID userId, Window window, long limit) {
        return jdbcClient
                .sql(TABLE_ROWS_SQL)
                .paramSource(source(userId, window).addValue("limit", limit))
                .query((rs, rowNum) -> mapTableRow(rs))
                .list();
    }

    private static Map<String, List<TableRow>> indexRowsByGroup(List<TableRow> rows) {
        Map<String, List<TableRow>> indexed = new HashMap<>();
        for (TableRow row : rows) {
            indexed.computeIfAbsent(
                            groupKeyOf(TxType.valueOf(row.type()), row.categoryId()),
                            key -> new ArrayList<>())
                    .add(row);
        }
        return indexed;
    }

    private static String groupKeyOf(TxType type, UUID categoryId) {
        return type.name() + "|" + categoryId;
    }

    private static BudgetRow budgetFor(List<BudgetRow> budgets, UUID categoryId) {
        for (BudgetRow budget : budgets) {
            if (budget.categoryId().equals(categoryId)) {
                return budget;
            }
        }
        return null;
    }

    /** Budget harian diskalakan × jumlah hari rentang; bulanan dibandingkan langsung. */
    private static long effectiveBudget(BudgetPeriod period, long amount, Window window) {
        if (period == BudgetPeriod.MONTHLY) {
            return amount;
        }
        long days = ChronoUnit.DAYS.between(window.from(), window.to()) + 1;
        return amount * days;
    }

    private static TxType typeOf(String raw) {
        return TxType.valueOf(raw.toUpperCase(Locale.ROOT));
    }

    private static CategoryRow mapCategoryRow(ResultSet rs) throws SQLException {
        return new CategoryRow(
                typeOf(rs.getString("type")),
                rs.getObject("category_id", UUID.class),
                rs.getString("category_name"),
                rs.getBoolean("category_archived"),
                rs.getLong("amount"));
    }

    private static BudgetRow mapBudgetRow(ResultSet rs) throws SQLException {
        return new BudgetRow(
                rs.getObject("category_id", UUID.class),
                rs.getString("category_name"),
                rs.getBoolean("category_archived"),
                BudgetPeriod.valueOf(rs.getString("period").toUpperCase(Locale.ROOT)),
                rs.getLong("budget_amount"),
                rs.getLong("spent"));
    }

    private static SubtotalRow mapSubtotalRow(ResultSet rs) throws SQLException {
        return new SubtotalRow(
                rs.getObject("category_id", UUID.class),
                rs.getString("category_name"),
                rs.getBoolean("category_archived"),
                typeOf(rs.getString("type")),
                rs.getLong("subtotal"));
    }

    private static TableRow mapTableRow(ResultSet rs) throws SQLException {
        return new TableRow(
                rs.getObject("id", UUID.class),
                rs.getObject("date", LocalDate.class),
                typeOf(rs.getString("type")).name(),
                rs.getObject("category_id", UUID.class),
                rs.getString("category_name"),
                rs.getString("description"),
                rs.getLong("amount"));
    }
}
