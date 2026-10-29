package com.fastwork.expense.transaction;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fastwork.expense.category.CategoryService;
import com.fastwork.expense.common.ApiException;
import com.fastwork.expense.common.TxType;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TransactionService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private static final String SELECT_BASE =
            """
            select t.id, t.date, t.type, t.category_id, c.name as category_name,
                   t.description, t.amount, t.created_at, t.updated_at
            from transactions t
            left join categories c on c.id = t.category_id
            """;

    private static final RowMapper<TransactionResponse> ROW_MAPPER = TransactionService::mapRow;

    private final JdbcClient jdbcClient;
    private final TransactionRepository transactionRepository;
    private final CategoryService categoryService;

    /**
     * Filter daftar transaksi; semua field boleh null (tidak difilter). {@code q} dicari dengan
     * ILIKE pada kolom description.
     */
    public record Filter(LocalDate from, LocalDate to, TxType type, UUID categoryId, String q) {}

    @Transactional(readOnly = true)
    public TransactionPage page(
            UUID userId, Filter filter, TransactionSort sort, String cursor, int requestedLimit) {
        int limit = Math.min(Math.max(requestedLimit, 1), MAX_LIMIT);
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder("t.closing_id is null and t.user_id = :userId");
        params.addValue("userId", userId);
        appendFilters(where, params, filter);
        if (cursor != null) {
            appendKeyset(sort, cursor, where, params);
        }
        String sql =
                SELECT_BASE
                        + " where "
                        + where
                        + " order by "
                        + orderBy(sort)
                        + " limit "
                        + (limit + 1);
        List<TransactionResponse> rows =
                jdbcClient.sql(sql).paramSource(params).query(ROW_MAPPER).list();
        boolean hasNext = rows.size() > limit;
        List<TransactionResponse> items = hasNext ? rows.subList(0, limit) : rows;
        String nextCursor = hasNext ? cursorFor(sort, items.get(items.size() - 1)) : null;
        return new TransactionPage(items, nextCursor, totals(userId, filter));
    }

    @Transactional
    public TransactionResponse upsert(UUID userId, UUID id, UpsertTransactionRequest request) {
        validateCategory(userId, request.type(), request.categoryId());
        String sql =
                """
                insert into transactions (id, user_id, date, type, category_id, description, amount, closing_id)
                values (:id, :userId, :date, cast(:type as tx_type), :categoryId, :description, :amount, null)
                on conflict (id) do update set
                  date = excluded.date,
                  type = excluded.type,
                  category_id = excluded.category_id,
                  description = excluded.description,
                  amount = excluded.amount,
                  updated_at = now()
                where transactions.user_id = :userId
                returning id
                """;
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("userId", userId)
                        .addValue("date", request.date())
                        .addValue("type", request.type().name().toLowerCase(Locale.ROOT))
                        .addValue("categoryId", request.categoryId())
                        .addValue("description", normalizeDescription(request.description()))
                        .addValue("amount", request.amount());
        Optional<UUID> saved = jdbcClient.sql(sql).paramSource(params).query(UUID.class).optional();
        if (saved.isEmpty()) {
            throw ApiException.notFound("Transaksi tidak ditemukan");
        }
        return get(userId, id);
    }

    @Transactional
    public TransactionResponse update(UUID userId, UUID id, UpdateTransactionRequest request) {
        Transaction tx =
                transactionRepository
                        .findByIdAndUserId(id, userId)
                        .orElseThrow(() -> ApiException.notFound("Transaksi tidak ditemukan"));
        TxType type = request.type() != null ? request.type() : tx.getType();
        UUID categoryId = request.categoryId() != null ? request.categoryId() : tx.getCategoryId();
        if (request.type() != null || request.categoryId() != null) {
            validateCategory(userId, type, categoryId);
        }
        if (request.date() != null) {
            tx.setDate(request.date());
        }
        if (request.type() != null) {
            tx.setType(request.type());
        }
        if (request.categoryId() != null) {
            tx.setCategoryId(request.categoryId());
        }
        if (request.description() != null) {
            tx.setDescription(normalizeDescription(request.description()));
        }
        if (request.amount() != null) {
            tx.setAmount(request.amount());
        }
        transactionRepository.saveAndFlush(tx);
        String categoryName = categoryService.nameOf(userId, tx.getCategoryId());
        return fromEntity(tx, categoryName);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        Transaction tx =
                transactionRepository
                        .findByIdAndUserId(id, userId)
                        .orElseThrow(() -> ApiException.notFound("Transaksi tidak ditemukan"));
        transactionRepository.delete(tx);
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(UUID userId, UUID id) {
        MapSqlParameterSource params =
                new MapSqlParameterSource().addValue("userId", userId).addValue("id", id);
        List<TransactionResponse> rows =
                jdbcClient
                        .sql(
                                SELECT_BASE
                                        + " where t.closing_id is null and t.user_id = :userId and t.id = :id")
                        .paramSource(params)
                        .query(ROW_MAPPER)
                        .list();
        if (rows.isEmpty()) {
            throw ApiException.notFound("Transaksi tidak ditemukan");
        }
        return rows.get(0);
    }

    private TransactionTotals totals(UUID userId, Filter filter) {
        StringBuilder where = new StringBuilder("t.closing_id is null and t.user_id = :userId");
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("userId", userId);
        appendFilters(where, params, filter);
        String sql =
                """
                select coalesce(sum(case when t.type = 'income' then t.amount end), 0) as income,
                       coalesce(sum(case when t.type = 'expense' then t.amount end), 0) as expense
                from transactions t
                left join categories c on c.id = t.category_id
                """
                        + " where "
                        + where;
        return Optional.ofNullable(
                        jdbcClient
                                .sql(sql)
                                .paramSource(params)
                                .query(
                                        (rs, i) ->
                                                TransactionTotals.of(
                                                        rs.getLong("income"),
                                                        rs.getLong("expense")))
                                .list()
                                .get(0))
                .orElse(TransactionTotals.of(0, 0));
    }

    private static void appendFilters(
            StringBuilder where, MapSqlParameterSource params, Filter filter) {
        if (filter.from() != null) {
            where.append(" and t.date >= :from");
            params.addValue("from", filter.from());
        }
        if (filter.to() != null) {
            where.append(" and t.date <= :to");
            params.addValue("to", filter.to());
        }
        if (filter.type() != null) {
            where.append(" and t.type = cast(:type as tx_type)");
            params.addValue("type", filter.type().name().toLowerCase(Locale.ROOT));
        }
        if (filter.categoryId() != null) {
            where.append(" and t.category_id = :categoryId");
            params.addValue("categoryId", filter.categoryId());
        }
        String q = filter.q() == null ? "" : filter.q().trim();
        if (!q.isEmpty()) {
            where.append(" and t.description ilike :q escape '\\'");
            params.addValue("q", "%" + escapeLike(q) + "%");
        }
    }

    private static void appendKeyset(
            TransactionSort sort,
            String cursor,
            StringBuilder where,
            MapSqlParameterSource params) {
        String[] parts;
        try {
            parts = decodeCursor(cursor);
            applyKeyset(sort, parts, where, params);
        } catch (DateTimeParseException | NumberFormatException ex) {
            throw badCursor();
        }
    }

    private static void applyKeyset(
            TransactionSort sort,
            String[] parts,
            StringBuilder where,
            MapSqlParameterSource params) {
        switch (sort) {
            case DATE_DESC, DATE_ASC -> {
                if (parts.length != 3) {
                    throw badCursor();
                }
                where.append(
                        sort == TransactionSort.DATE_DESC
                                ? " and (t.date, t.created_at, t.id) < (:kDate, :kTime, :kId)"
                                : " and (t.date, t.created_at, t.id) > (:kDate, :kTime, :kId)");
                params.addValue("kDate", LocalDate.parse(parts[0]));
                params.addValue(
                        "kTime", OffsetDateTime.ofInstant(Instant.parse(parts[1]), ZoneOffset.UTC));
                params.addValue("kId", UUID.fromString(parts[2]));
            }
            case AMOUNT_DESC, AMOUNT_ASC -> {
                if (parts.length != 2) {
                    throw badCursor();
                }
                where.append(
                        sort == TransactionSort.AMOUNT_DESC
                                ? " and (t.amount, t.id) < (:kAmount, :kId)"
                                : " and (t.amount, t.id) > (:kAmount, :kId)");
                params.addValue("kAmount", Long.parseLong(parts[0]));
                params.addValue("kId", UUID.fromString(parts[1]));
            }
            case CATEGORY_ASC, CATEGORY_DESC -> {
                if (parts.length != 2) {
                    throw badCursor();
                }
                where.append(
                        sort == TransactionSort.CATEGORY_ASC
                                ? " and (coalesce(c.name, ''), t.id) > (:kName, :kId)"
                                : " and (coalesce(c.name, ''), t.id) < (:kName, :kId)");
                params.addValue("kName", parts[0]);
                params.addValue("kId", UUID.fromString(parts[1]));
            }
        }
    }

    private static String orderBy(TransactionSort sort) {
        return switch (sort) {
            case DATE_DESC -> "t.date desc, t.created_at desc, t.id desc";
            case DATE_ASC -> "t.date asc, t.created_at asc, t.id asc";
            case AMOUNT_DESC -> "t.amount desc, t.id desc";
            case AMOUNT_ASC -> "t.amount asc, t.id asc";
            case CATEGORY_ASC -> "coalesce(c.name, '') asc, t.id asc";
            case CATEGORY_DESC -> "coalesce(c.name, '') desc, t.id desc";
        };
    }

    private static String cursorFor(TransactionSort sort, TransactionResponse last) {
        return switch (sort) {
            case DATE_DESC, DATE_ASC ->
                    encodeCursor(
                            last.date().toString(),
                            last.createdAt().toString(),
                            last.id().toString());
            case AMOUNT_DESC, AMOUNT_ASC ->
                    encodeCursor(String.valueOf(last.amount()), last.id().toString());
            case CATEGORY_ASC, CATEGORY_DESC ->
                    encodeCursor(
                            last.categoryName() == null ? "" : last.categoryName(),
                            last.id().toString());
        };
    }

    private void validateCategory(UUID userId, TxType type, UUID categoryId) {
        if (categoryId == null) {
            if (type.isIncome()) {
                throw ApiException.unprocessable("Pemasukan wajib punya sumber");
            }
            return;
        }
        categoryService.requireUsable(userId, categoryId, type);
    }

    private static TransactionResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TransactionResponse(
                rs.getObject("id", UUID.class),
                rs.getDate("date").toLocalDate(),
                TxType.valueOf(rs.getString("type").toUpperCase(Locale.ROOT)),
                rs.getObject("category_id", UUID.class),
                rs.getString("category_name"),
                rs.getString("description"),
                rs.getLong("amount"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static TransactionResponse fromEntity(Transaction tx, String categoryName) {
        return new TransactionResponse(
                tx.getId(),
                tx.getDate(),
                tx.getType(),
                tx.getCategoryId(),
                categoryName,
                tx.getDescription(),
                tx.getAmount(),
                tx.getCreatedAt(),
                tx.getUpdatedAt());
    }

    private static String normalizeDescription(String description) {
        if (description == null) {
            return "";
        }
        return description.strip();
    }

    private static String escapeLike(String q) {
        return q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String encodeCursor(String... parts) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
    }

    private static String[] decodeCursor(String cursor) {
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                    .split("\\|", -1);
        } catch (IllegalArgumentException ex) {
            throw badCursor();
        }
    }

    private static ApiException badCursor() {
        return ApiException.badRequest("INVALID_CURSOR", "Cursor tidak valid");
    }
}
