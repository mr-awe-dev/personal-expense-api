package com.fastwork.expense.today;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TodayService {

    /**
     * Rincian kategori yang punya budget HARIAN, beserta terpakai hari ini. Hanya transaksi
     * berjalan (closing_id kosong) yang dihitung; income tidak mengurangi budget.
     */
    private static final String DAILY_CATEGORIES_SQL =
            """
            select b.category_id, c.name as category_name, c.archived as category_archived,
                   b.amount as limit_amount, coalesce(s.spent, 0) as spent
            from budgets b
            join categories c on c.id = b.category_id
            left join (
                select t.category_id, sum(t.amount) as spent
                from transactions t
                where t.user_id = :userId
                  and t.date = :date
                  and t.type = 'expense'
                  and t.closing_id is null
                  and t.category_id is not null
                group by t.category_id
            ) s on s.category_id = b.category_id
            where b.user_id = :userId and b.period = 'daily'
            order by c.name asc, b.category_id asc
            """;

    private static final String SPENT_TODAY_SQL =
            """
            select coalesce(sum(amount), 0) as spent
            from transactions
            where user_id = :userId and date = :date
              and type = 'expense' and closing_id is null
            """;

    private static final RowMapper<TodayCategory> CATEGORY_MAPPER = TodayService::mapCategory;

    private final JdbcClient jdbcClient;

    @Transactional(readOnly = true)
    public TodayResponse today(UUID userId, Long dailyLimit, String timezone, LocalDate requested) {
        LocalDate date = requested != null ? requested : localToday(timezone);
        long spentToday = spentToday(userId, date);
        Long remaining = null;
        TodayStatus status = null;
        if (dailyLimit != null) {
            long limit = dailyLimit;
            remaining = limit - spentToday;
            status = TodayStatus.of(limit, spentToday);
        }
        return new TodayResponse(
                date, dailyLimit, spentToday, remaining, status, dailyCategoryRows(userId, date));
    }

    /** "Hari ini" mengikuti zona waktu profil user (aturan §10 poin 9). */
    private static LocalDate localToday(String timezone) {
        try {
            return LocalDate.now(ZoneId.of(timezone));
        } catch (DateTimeException invalid) {
            return LocalDate.now(ZoneOffset.UTC);
        }
    }

    private long spentToday(UUID userId, LocalDate date) {
        List<Long> rows =
                jdbcClient
                        .sql(SPENT_TODAY_SQL)
                        .paramSource(
                                new MapSqlParameterSource()
                                        .addValue("userId", userId)
                                        .addValue("date", date))
                        .query((rs, rowNum) -> rs.getLong("spent"))
                        .list();
        return rows.isEmpty() ? 0L : rows.getFirst();
    }

    private List<TodayCategory> dailyCategoryRows(UUID userId, LocalDate date) {
        return jdbcClient
                .sql(DAILY_CATEGORIES_SQL)
                .paramSource(
                        new MapSqlParameterSource()
                                .addValue("userId", userId)
                                .addValue("date", date))
                .query(CATEGORY_MAPPER)
                .list();
    }

    private static TodayCategory mapCategory(ResultSet rs, int rowNum) throws SQLException {
        long limitAmount = rs.getLong("limit_amount");
        long spent = rs.getLong("spent");
        return new TodayCategory(
                rs.getObject("category_id", UUID.class),
                rs.getString("category_name"),
                rs.getBoolean("category_archived"),
                limitAmount,
                spent,
                limitAmount - spent);
    }
}
