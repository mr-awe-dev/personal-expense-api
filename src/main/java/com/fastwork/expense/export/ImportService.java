package com.fastwork.expense.export;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fastwork.expense.budget.BudgetPeriod;
import com.fastwork.expense.common.ApiException;
import com.fastwork.expense.common.TxType;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ImportService {

    private static final int SUPPORTED_VERSION = 1;

    private record CatRef(UUID id, TxType type) {}

    private static final class ImportState {

        final Map<UUID, CatRef> categoriesById = new HashMap<>();
        final Map<UUID, UUID> closingIds = new HashMap<>();
        int categoryCount;
        int closingCount;
        int budgetCount;
        int transactionCount;
    }

    private final JdbcClient jdbcClient;

    @Transactional
    public ImportResult importFile(UUID userId, BackupFile file) {
        if (file == null || file.version() != SUPPORTED_VERSION) {
            throw ApiException.unprocessable(
                    "Format backup tidak didukung (versi "
                            + (file == null ? "?" : file.version())
                            + ")");
        }
        ImportState state = new ImportState();
        Map<UUID, CatRef> preexistingCategories = loadCategoryRefs(userId);
        Set<UUID> preexistingClosings = loadOwnedClosingIds(userId);
        applyProfile(userId, file.user());
        importCategories(userId, file.categories(), state);
        importClosings(userId, file.closings(), state);
        importBudgets(userId, file.budgets(), state, preexistingCategories);
        importTransactions(
                userId, file.transactions(), state, preexistingCategories, preexistingClosings);
        return new ImportResult(
                state.categoryCount, state.transactionCount, state.budgetCount, state.closingCount);
    }

    private Map<UUID, CatRef> loadCategoryRefs(UUID userId) {
        Map<UUID, CatRef> refs = new HashMap<>();
        jdbcClient
                .sql("select id, type from categories where user_id = :userId")
                .param("userId", userId)
                .query(
                        (rs, rowNum) -> {
                            refs.put(
                                    rs.getObject("id", UUID.class),
                                    new CatRef(
                                            rs.getObject("id", UUID.class),
                                            txType(rs.getString("type"))));
                            return 1;
                        })
                .list();
        return refs;
    }

    private Set<UUID> loadOwnedClosingIds(UUID userId) {
        return new HashSet<>(
                jdbcClient
                        .sql("select id from closings where user_id = :userId")
                        .param("userId", userId)
                        .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                        .list());
    }

    private void applyProfile(UUID userId, BackupFile.BackupProfile profile) {
        if (profile == null) {
            return;
        }
        if (profile.displayName() != null) {
            jdbcClient
                    .sql("update users set display_name = :displayName where id = :userId")
                    .param("displayName", profile.displayName().strip())
                    .param("userId", userId)
                    .update();
        }
        if (profile.timezone() != null) {
            jdbcClient
                    .sql("update users set timezone = :timezone where id = :userId")
                    .param("timezone", profile.timezone().strip())
                    .param("userId", userId)
                    .update();
        }
        if (profile.dailyLimit() != null) {
            jdbcClient
                    .sql("update users set daily_limit = :dailyLimit where id = :userId")
                    .param("dailyLimit", profile.dailyLimit())
                    .param("userId", userId)
                    .update();
        }
    }

    private void importCategories(
            UUID userId, List<BackupFile.BackupCategory> rows, ImportState state) {
        if (rows == null) {
            return;
        }
        Map<UUID, TxType> typesById = new HashMap<>();
        Map<String, UUID> idByTypeAndKey = new HashMap<>();
        jdbcClient
                .sql("select id, type, name_key from categories where user_id = :userId")
                .param("userId", userId)
                .query(
                        (rs, rowNum) -> {
                            UUID id = rs.getObject("id", UUID.class);
                            TxType type = txType(rs.getString("type"));
                            typesById.put(id, type);
                            idByTypeAndKey.put(
                                    type.name().toLowerCase(Locale.ROOT)
                                            + "|"
                                            + rs.getString("name_key"),
                                    id);
                            return id;
                        })
                .list();

        String insertSql =
                """
                insert into categories (id, user_id, type, name, name_key, archived, created_at, updated_at)
                values (:id, :userId, cast(:type as tx_type), :name, :nameKey, :archived, :createdAt, :updatedAt)
                """;
        String updateSql =
                """
                update categories
                   set name = :name, name_key = :nameKey, archived = :archived, updated_at = now()
                 where id = :id and user_id = :userId
                """;

        for (BackupFile.BackupCategory row : rows) {
            if (row.id() == null) {
                throw ApiException.unprocessable("Kategori backup tidak valid: id wajib ada");
            }
            UUID importedId = row.id();
            String typeLabel = typeLabel(row.type(), "Kategori backup tidak valid: tipe");
            TxType type = parseTxType(typeLabel, "Kategori backup tidak valid: tipe");
            String name = row.name() == null ? "Kategori" : row.name().strip();
            if (name.isEmpty()) {
                name = "Kategori";
            }
            String nameKey = nameKey(name);
            boolean archived = row.archived() != null && row.archived();

            UUID existingId = idByTypeAndKey.get(typeLabel + "|" + nameKey);
            if (existingId != null) {
                // Kategori (tipe, nama) yang sama sudah ada di buku kas: Favorit server
                // & referensi di file backup di-remap ke id yang sudah ada.
                state.categoriesById.put(
                        importedId, new CatRef(existingId, typesById.get(existingId)));
                continue;
            }

            if (typesById.containsKey(importedId)) {
                jdbcClient
                        .sql(updateSql)
                        .param("name", name)
                        .param("nameKey", nameKey)
                        .param("archived", archived)
                        .param("id", importedId)
                        .param("userId", userId)
                        .update();
                state.categoriesById.put(importedId, new CatRef(importedId, type));
            } else {
                jdbcClient
                        .sql(insertSql)
                        .paramSource(
                                new MapSqlParameterSource()
                                        .addValue("id", importedId)
                                        .addValue("userId", userId)
                                        .addValue("type", typeLabel)
                                        .addValue("name", name)
                                        .addValue("nameKey", nameKey)
                                        .addValue("archived", archived)
                                        .addValue("createdAt", ts(instantOrNow(row.createdAt())))
                                        .addValue("updatedAt", ts(instantOrNow(row.updatedAt()))))
                        .update();
                state.categoriesById.put(importedId, new CatRef(importedId, type));
                typesById.put(importedId, type);
            }
            idByTypeAndKey.put(
                    typeLabel + "|" + nameKey, state.categoriesById.get(importedId).id());
            state.categoryCount++;
        }
    }

    private void importClosings(
            UUID userId, List<BackupFile.BackupClosing> rows, ImportState state) {
        if (rows == null) {
            return;
        }
        String updateSql =
                """
                update closings
                   set period_start = :periodStart, period_end = :periodEnd,
                       total_income = :totalIncome, total_expense = :totalExpense, net = :net,
                       tx_count = :txCount, file_name = :fileName, closed_at = :closedAt
                 where id = :id and user_id = :userId
                """;
        String insertSql =
                """
                insert into closings (id, user_id, period_start, period_end, total_income,
                                      total_expense, net, tx_count, file_name, closed_at)
                values (:id, :userId, :periodStart, :periodEnd, :totalIncome, :totalExpense,
                        :net, :txCount, :fileName, :closedAt)
                """;
        Set<UUID> owned =
                new HashSet<>(
                        jdbcClient
                                .sql("select id from closings where user_id = :userId")
                                .param("userId", userId)
                                .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                                .list());

        for (BackupFile.BackupClosing row : rows) {
            if (row.id() == null) {
                throw ApiException.unprocessable("Arsip backup tidak valid: id wajib ada");
            }
            UUID id = row.id();
            if (row.periodStart() == null
                    || row.periodEnd() == null
                    || row.periodEnd().isBefore(row.periodStart())) {
                throw ApiException.unprocessable("Arsip backup tidak valid: periode");
            }
            if (row.fileName() == null || row.fileName().isBlank()) {
                throw ApiException.unprocessable("Arsip backup tidak valid: file_name");
            }
            MapSqlParameterSource params =
                    new MapSqlParameterSource()
                            .addValue("id", id)
                            .addValue("userId", userId)
                            .addValue("periodStart", row.periodStart())
                            .addValue("periodEnd", row.periodEnd())
                            .addValue(
                                    "totalIncome",
                                    row.totalIncome() == null ? 0 : row.totalIncome())
                            .addValue(
                                    "totalExpense",
                                    row.totalExpense() == null ? 0 : row.totalExpense())
                            .addValue("net", row.net() == null ? 0 : row.net())
                            .addValue("txCount", row.txCount() == null ? 0 : row.txCount())
                            .addValue("fileName", row.fileName())
                            .addValue("closedAt", ts(instantOrNow(row.closedAt())));
            if (owned.contains(id)) {
                jdbcClient.sql(updateSql).paramSource(params).update();
            } else {
                Long clash =
                        jdbcClient
                                .sql("select count(*) from closings where id = :id")
                                .param("id", id)
                                .query((rs, rowNum) -> rs.getLong(1))
                                .list()
                                .getFirst();
                if (clash > 0) {
                    throw ApiException.unprocessable(
                            "Backup tidak valid: ID arsip sudah dipakai data lain");
                }
                jdbcClient.sql(insertSql).paramSource(params).update();
                owned.add(id);
            }
            state.closingIds.put(id, id);
            state.closingCount++;
        }
    }

    private void importBudgets(
            UUID userId,
            List<BackupFile.BackupBudget> rows,
            ImportState state,
            Map<UUID, CatRef> preexistingCategories) {
        if (rows == null) {
            return;
        }
        Map<UUID, UUID> budgetIdByCategory = new HashMap<>();
        jdbcClient
                .sql("select id, category_id from budgets where user_id = :userId")
                .param("userId", userId)
                .query(
                        (rs, rowNum) -> {
                            budgetIdByCategory.put(
                                    rs.getObject("category_id", UUID.class),
                                    rs.getObject("id", UUID.class));
                            return rs.getObject("id", UUID.class);
                        })
                .list();

        String updateByCategorySql =
                """
                update budgets
                   set period = cast(:period as budget_period), amount = :amount,
                       description = :description, updated_at = now()
                 where id = :id and user_id = :userId
                """;
        String updateByIdSql =
                """
                update budgets
                   set category_id = :categoryId, period = cast(:period as budget_period),
                       amount = :amount, description = :description, updated_at = now()
                 where id = :id and user_id = :userId
                """;
        String insertSql =
                """
                insert into budgets (id, user_id, category_id, period, amount, description, created_at, updated_at)
                values (:id, :userId, :categoryId, cast(:period as budget_period), :amount,
                        :description, :createdAt, :updatedAt)
                """;

        for (BackupFile.BackupBudget row : rows) {
            String periodLabel = typeLabel(row.period(), "Budget backup tidak valid: period");
            try {
                BudgetPeriod.valueOf(periodLabel.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw ApiException.unprocessable("Budget backup tidak valid: period");
            }
            long amount = row.amount() == null ? 0 : row.amount();
            if (amount < 0) {
                throw ApiException.unprocessable("Budget backup tidak valid: amount");
            }
            if (row.categoryId() == null) {
                throw ApiException.unprocessable("Budget merujuk kategori yang tidak ada");
            }
            CatRef resolved =
                    resolveCategory(row.categoryId(), state, preexistingCategories, "Budget");
            UUID categoryId = resolved.id();
            String description = row.description() == null ? "" : row.description().strip();

            UUID byCategory = budgetIdByCategory.get(categoryId);
            if (byCategory != null) {
                jdbcClient
                        .sql(updateByCategorySql)
                        .param("period", periodLabel)
                        .param("amount", amount)
                        .param("description", description)
                        .param("id", byCategory)
                        .param("userId", userId)
                        .update();
                state.budgetCount++;
                continue;
            }
            if (row.id() == null) {
                throw ApiException.unprocessable("Budget backup tidak valid: id wajib ada");
            }
            Long ownedById =
                    jdbcClient
                            .sql(
                                    "select count(*) from budgets"
                                            + " where id = :id and user_id = :userId")
                            .param("id", row.id())
                            .param("userId", userId)
                            .query((rs, rowNum) -> rs.getLong(1))
                            .list()
                            .getFirst();
            if (ownedById > 0) {
                jdbcClient
                        .sql(updateByIdSql)
                        .param("categoryId", categoryId)
                        .param("period", periodLabel)
                        .param("amount", amount)
                        .param("description", description)
                        .param("id", row.id())
                        .param("userId", userId)
                        .update();
            } else {
                Long clash =
                        jdbcClient
                                .sql("select count(*) from budgets where id = :id")
                                .param("id", row.id())
                                .query((rs, rowNum) -> rs.getLong(1))
                                .list()
                                .getFirst();
                if (clash > 0) {
                    throw ApiException.unprocessable(
                            "Backup tidak valid: ID budget sudah dipakai data lain");
                }
                jdbcClient
                        .sql(insertSql)
                        .paramSource(
                                new MapSqlParameterSource()
                                        .addValue("id", row.id())
                                        .addValue("userId", userId)
                                        .addValue("categoryId", categoryId)
                                        .addValue("period", periodLabel)
                                        .addValue("amount", amount)
                                        .addValue("description", description)
                                        .addValue("createdAt", ts(instantOrNow(row.createdAt())))
                                        .addValue("updatedAt", ts(instantOrNow(row.updatedAt()))))
                        .update();
            }
            budgetIdByCategory.put(categoryId, row.id());
            state.budgetCount++;
        }
    }

    private void importTransactions(
            UUID userId,
            List<BackupFile.BackupTransaction> rows,
            ImportState state,
            Map<UUID, CatRef> preexistingCategories,
            Set<UUID> preexistingClosings) {
        if (rows == null) {
            return;
        }
        String upsertSql =
                """
                insert into transactions (id, user_id, date, type, category_id, description,
                                          amount, closing_id, created_at, updated_at)
                values (:id, :userId, :date, cast(:type as tx_type), :categoryId, :description,
                        :amount, :closingId, :createdAt, :updatedAt)
                on conflict (id) do update set
                  date = excluded.date,
                  type = excluded.type,
                  category_id = excluded.category_id,
                  description = excluded.description,
                  amount = excluded.amount,
                  closing_id = excluded.closing_id,
                  created_at = excluded.created_at,
                  updated_at = excluded.updated_at
                where transactions.user_id = :userId
                returning id
                """;

        for (BackupFile.BackupTransaction row : rows) {
            if (row.id() == null) {
                throw ApiException.unprocessable("Transaksi backup tidak valid: id wajib ada");
            }
            UUID id = row.id();
            if (row.date() == null) {
                throw ApiException.unprocessable("Transaksi backup tidak valid: tanggal");
            }
            String typeLabel = typeLabel(row.type(), "Transaksi backup tidak valid: tipe");
            TxType type = parseTxType(typeLabel, "Transaksi backup tidak valid: tipe");
            long amount = row.amount() == null ? 0 : row.amount();
            if (amount <= 0) {
                throw ApiException.unprocessable("Transaksi backup tidak valid: amount");
            }
            UUID categoryId = null;
            if (row.categoryId() != null) {
                ImportService.CatRef ref =
                        resolveCategory(
                                row.categoryId(), state, preexistingCategories, "Transaksi");
                if (!ref.type().equals(type)) {
                    throw ApiException.unprocessable(
                            "Transaksi backup tidak valid: tipe kategori tidak sesuai");
                }
                categoryId = ref.id();
            }
            UUID closingId = null;
            if (row.closingId() != null) {
                if (!state.closingIds.containsKey(row.closingId())
                        && !preexistingClosings.contains(row.closingId())) {
                    throw ApiException.unprocessable("Transaksi merujuk arsip yang tidak ada");
                }
                closingId = row.closingId();
            }

            MapSqlParameterSource params =
                    new MapSqlParameterSource()
                            .addValue("id", id)
                            .addValue("userId", userId)
                            .addValue("date", row.date())
                            .addValue("type", typeLabel)
                            .addValue("categoryId", categoryId)
                            .addValue(
                                    "description",
                                    row.description() == null ? "" : row.description().strip())
                            .addValue("amount", amount)
                            .addValue("closingId", closingId)
                            .addValue("createdAt", ts(instantOrNow(row.createdAt())))
                            .addValue("updatedAt", ts(instantOrNow(row.updatedAt())));
            var saved = jdbcClient.sql(upsertSql).paramSource(params).query(UUID.class).optional();
            if (saved.isEmpty()) {
                throw ApiException.unprocessable(
                        "Backup tidak valid: ID transaksi sudah dipakai data lain");
            }
            state.transactionCount++;
        }
    }

    private static String nameKey(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private CatRef resolveCategory(
            UUID categoryId,
            ImportState state,
            Map<UUID, CatRef> preexistingCategories,
            String what) {
        CatRef ref = state.categoriesById.get(categoryId);
        if (ref == null) {
            ref = preexistingCategories.get(categoryId);
        }
        if (ref == null) {
            throw ApiException.unprocessable(what + " merujuk kategori yang tidak ada");
        }
        return ref;
    }

    private static java.time.OffsetDateTime ts(Instant instant) {
        return java.time.OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }

    private static String typeLabel(String value, String context) {
        if (value == null) {
            throw ApiException.unprocessable(context + " wajib ada");
        }
        String label = value.strip().toLowerCase(Locale.ROOT);
        if (label.isEmpty()) {
            throw ApiException.unprocessable(context + " wajib ada");
        }
        return label;
    }

    private static TxType txType(String label) {
        return TxType.valueOf(label.toUpperCase(Locale.ROOT));
    }

    private static TxType parseTxType(String label, String context) {
        try {
            return TxType.valueOf(label.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ApiException.unprocessable(context);
        }
    }

    private static Instant instantOrNow(Instant value) {
        return value == null ? Instant.now() : value;
    }
}
