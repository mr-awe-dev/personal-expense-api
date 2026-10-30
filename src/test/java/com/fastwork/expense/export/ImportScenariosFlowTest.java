package com.fastwork.expense.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import com.fastwork.expense.WebFlowTestBase;
import com.fastwork.expense.category.Category;
import com.fastwork.expense.category.CategoryService;
import com.fastwork.expense.common.TxType;
import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRepository;

/** Semua jalur validasi + merge import backup.json lewat HTTP sesuai spesifikasi §11. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImportScenariosFlowTest extends WebFlowTestBase {

    @Autowired private UserRepository userRepository;
    @Autowired private CategoryService categoryService;
    @Autowired private JdbcClient jdbcClient;

    private User user;
    private User other;
    private Cookie session;
    private Cookie otherSession;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();
        user = userService.createUser("pemilik@example.com", PASSWORD);
        other = userService.createUser("korban@example.com", PASSWORD);
        session = login("pemilik@example.com");
        otherSession = login("korban@example.com");
    }

    private String importBody(Object... kv) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        for (int i = 0; i < kv.length; i += 2) {
            root.put((String) kv[i], kv[i + 1]);
        }
        try {
            return json(root);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private org.springframework.test.web.servlet.ResultActions postImport(String body, Cookie s)
            throws Exception {
        return mockMvc.perform(
                authorized(post("/api/v1/import"), s)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private void postImportExpect422(String body, String messagePart) throws Exception {
        postImport(body, session)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(
                        result ->
                                assertThat(result.getResponse().getContentAsString())
                                        .contains(messagePart));
    }

    private static Map<String, Object> category(
            UUID id, String type, String name, boolean archived) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (id != null) {
            row.put("id", id.toString());
        }
        if (type != null) {
            row.put("type", type);
        }
        row.put("name", name);
        row.put("archived", archived);
        return row;
    }

    private long count(String table, UUID userId) {
        return jdbcClient
                .sql("select count(*) from " + table + " where user_id = :u")
                .param("u", userId)
                .query(Long.class)
                .single();
    }

    @Test
    void wrongVersionRejected() throws Exception {
        postImport("{\"version\": 2}", session)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(
                        result ->
                                assertThat(result.getResponse().getContentAsString())
                                        .contains("Format backup tidak didukung (versi 2)"));
    }

    @Test
    void minimalVersionImportsNothing() throws Exception {
        postImport(importBody(), session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").value(0))
                .andExpect(jsonPath("$.transactions").value(0));
        assertThat(count("categories", user.getId())).isZero();
    }

    @Test
    void profileFieldsAreAppliedAndTrimmed() throws Exception {
        postImport(
                        importBody(
                                "user",
                                Map.of(
                                        "displayName", "  Baru  ",
                                        "timezone", " Asia/Makassar ",
                                        "dailyLimit", 45000L)),
                        session)
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/me").cookie(session))
                .andExpect(jsonPath("$.displayName").value("Baru"))
                .andExpect(jsonPath("$.timezone").value("Asia/Makassar"))
                .andExpect(jsonPath("$.dailyLimit").value(45000));
    }

    @Test
    void categoryRowsMustHaveIdAndUsableType() throws Exception {
        postImportExpect422(
                importBody("categories", List.of(category(null, "expense", "Tanpa Id", false))),
                "id wajib ada");
        postImportExpect422(
                importBody("categories", List.of(category(UUID.randomUUID(), null, "X", false))),
                "tipe");
        postImportExpect422(
                importBody("categories", List.of(category(UUID.randomUUID(), "  ", "X", false))),
                "tipe");
        postImportExpect422(
                importBody("categories", List.of(category(UUID.randomUUID(), "bogus", "X", false))),
                "tipe");
    }

    @Test
    void blankNamesBecomePlaceholderAndRemapDuplicates() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        postImport(
                        importBody(
                                "categories",
                                List.of(
                                        category(first, "expense", null, false),
                                        category(second, "expense", "   ", false))),
                        session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").value(1));

        assertThat(count("categories", user.getId())).isEqualTo(1);
        assertThat(
                        jdbcClient
                                .sql("select name from categories where user_id = :u")
                                .param("u", user.getId())
                                .query(String.class)
                                .single())
                .isEqualTo("Kategori");
    }

    @Test
    void sameNameExistingCategoryRemapsFileReferences() throws Exception {
        Category existing = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        UUID fileCategoryId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();

        postImport(
                        importBody(
                                "categories",
                                List.of(category(fileCategoryId, "expense", "makanan", false)),
                                "transactions",
                                List.of(
                                        transaction(
                                                txId,
                                                "2026-10-05",
                                                "expense",
                                                fileCategoryId,
                                                null,
                                                5000L))),
                        session)
                .andExpect(status().isOk());

        assertThat(
                        jdbcClient
                                .sql("select category_id from transactions where id = :id")
                                .param("id", txId)
                                .query(UUID.class)
                                .single())
                .isEqualTo(existing.getId());
    }

    @Test
    void sameIdDifferentNameUpdatesExistingRow() throws Exception {
        Category existing = categoryService.create(user.getId(), TxType.EXPENSE, "Lama");
        postImport(
                        importBody(
                                "categories",
                                List.of(category(existing.getId(), "expense", "  Baru  ", true))),
                        session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").value(1));

        Map<String, Object> row =
                jdbcClient
                        .sql("select name, archived, created_at from categories where id = :id")
                        .param("id", existing.getId())
                        .query(
                                (rs, n) -> {
                                    Map<String, Object> value = new LinkedHashMap<>();
                                    value.put("name", rs.getString("name"));
                                    value.put("archived", rs.getBoolean("archived"));
                                    value.put(
                                            "createdAt", rs.getTimestamp("created_at").toInstant());
                                    return value;
                                })
                        .single();
        assertThat(row.get("name")).isEqualTo("Baru");
        assertThat(row.get("archived")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void createdAtFromBackupIsHonoredOnInsert() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id.toString());
        row.put("type", "income");
        row.put("name", "Bonus Lama");
        row.put("createdAt", "2020-01-01T00:00:00Z");
        row.put("updatedAt", "2021-02-02T00:00:00Z");
        postImport(importBody("categories", List.of(row)), session).andExpect(status().isOk());

        assertThat(
                        jdbcClient
                                .sql("select created_at from categories where id = :id")
                                .param("id", id)
                                .query(Instant.class)
                                .single())
                .isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
    }

    private static Map<String, Object> transaction(
            UUID id, String date, String type, UUID categoryId, UUID closingId, Long amount) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (id != null) {
            row.put("id", id.toString());
        }
        if (date != null) {
            row.put("date", date);
        }
        if (type != null) {
            row.put("type", type);
        }
        if (categoryId != null) {
            row.put("categoryId", categoryId.toString());
        }
        if (closingId != null) {
            row.put("closingId", closingId.toString());
        }
        if (amount != null) {
            row.put("amount", amount);
        }
        return row;
    }

    private static Map<String, Object> closing(UUID id, String start, String end, String fileName) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (id != null) {
            row.put("id", id.toString());
        }
        if (start != null) {
            row.put("periodStart", start);
        }
        if (end != null) {
            row.put("periodEnd", end);
        }
        if (fileName != null) {
            row.put("fileName", fileName);
        }
        return row;
    }

    @Test
    void closingRowsAreValidatedStrictly() throws Exception {
        postImportExpect422(
                importBody(
                        "closings", List.of(closing(null, "2026-10-01", "2026-10-31", "a.xlsx"))),
                "id wajib ada");
        postImportExpect422(
                importBody(
                        "closings",
                        List.of(closing(UUID.randomUUID(), null, "2026-10-31", "a.xlsx"))),
                "periode");
        postImportExpect422(
                importBody(
                        "closings",
                        List.of(closing(UUID.randomUUID(), "2026-10-31", "2026-10-01", "a.xlsx"))),
                "periode");
        postImportExpect422(
                importBody(
                        "closings",
                        List.of(closing(UUID.randomUUID(), "2026-10-01", "2026-10-31", null))),
                "file_name");
        postImportExpect422(
                importBody(
                        "closings",
                        List.of(closing(UUID.randomUUID(), "2026-10-01", "2026-10-31", "   "))),
                "file_name");
    }

    @Test
    void closingInsertsDefaultsThenUpdatesOnReimport() throws Exception {
        UUID closingId = UUID.randomUUID();
        postImport(
                        importBody(
                                "closings",
                                List.of(
                                        closing(
                                                closingId,
                                                "2026-10-01",
                                                "2026-10-31",
                                                "arsip.xlsx"))),
                        session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closings").value(1));

        postImport(
                        importBody(
                                "closings",
                                List.of(
                                        withTotals(
                                                closing(
                                                        closingId,
                                                        "2026-10-01",
                                                        "2026-10-28",
                                                        "arsip2.xlsx"),
                                                111L,
                                                222L))),
                        session)
                .andExpect(status().isOk());

        assertThat(count("closings", user.getId())).isEqualTo(1);
        assertThat(
                        jdbcClient
                                .sql("select total_income from closings where id = :id")
                                .param("id", closingId)
                                .query(Long.class)
                                .single())
                .isEqualTo(111L);
    }

    private static Map<String, Object> withTotals(
            Map<String, Object> row, Long income, Long expense) {
        row.put("totalIncome", income);
        row.put("totalExpense", expense);
        row.put("net", income - expense);
        row.put("txCount", 7);
        return row;
    }

    @Test
    void closingIdOwnedByOtherUserIsRejected() throws Exception {
        UUID foreignClosing = UUID.randomUUID();
        insertForeignClosing(foreignClosing);

        postImportExpect422(
                importBody(
                        "closings",
                        List.of(closing(foreignClosing, "2026-10-01", "2026-10-31", "curi.xlsx"))),
                "ID arsip sudah dipakai data lain");
    }

    private void insertForeignClosing(UUID id) {
        jdbcClient
                .sql(
                        """
                        insert into closings (id, user_id, period_start, period_end,
                                              total_income, total_expense, net, tx_count, file_name)
                        values (:id, :u, date '2026-09-01', date '2026-09-30', 1, 2, -1, 3, 'x.xlsx')
                        """)
                .param("id", id)
                .param("u", other.getId())
                .update();
    }

    @Test
    void budgetRowsAreValidatedStrictly() throws Exception {
        Category expense = categoryService.create(user.getId(), TxType.EXPENSE, "Makan");
        postImportExpect422(
                importBody(
                        "budgets",
                        List.of(budget(UUID.randomUUID(), expense.getId(), "MINGGUAN", 1L))),
                "period");
        postImportExpect422(
                importBody(
                        "budgets", List.of(budget(UUID.randomUUID(), expense.getId(), null, 1L))),
                "period");
        postImportExpect422(
                importBody(
                        "budgets",
                        List.of(budget(UUID.randomUUID(), expense.getId(), "daily", -5L))),
                "amount");
        postImportExpect422(
                importBody("budgets", List.of(budget(UUID.randomUUID(), null, "daily", 5L))),
                "kategori yang tidak ada");
        postImportExpect422(
                importBody(
                        "budgets",
                        List.of(budget(UUID.randomUUID(), UUID.randomUUID(), "daily", 5L))),
                "kategori yang tidak ada");
    }

    private static Map<String, Object> budget(
            UUID id, UUID categoryId, String period, Long amount) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (id != null) {
            row.put("id", id.toString());
        }
        if (categoryId != null) {
            row.put("categoryId", categoryId.toString());
        }
        if (period != null) {
            row.put("period", period);
        }
        if (amount != null) {
            row.put("amount", amount);
        }
        row.put("description", "  intro  ");
        return row;
    }

    @Test
    void budgetAmountNullDefaultsToZeroAndIdRequiredForNewRow() throws Exception {
        Category expense = categoryService.create(user.getId(), TxType.EXPENSE, "Makan");
        UUID budgetId = UUID.randomUUID();
        postImport(
                        importBody(
                                "budgets",
                                List.of(budget(budgetId, expense.getId(), "DAILY", null))),
                        session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgets").value(1));
        assertThat(
                        jdbcClient
                                .sql("select amount, description from budgets where id = :id")
                                .param("id", budgetId)
                                .query((rs, n) -> rs.getLong(1) + "|" + rs.getString(2))
                                .single())
                .isEqualTo("0|intro");

        Category second = categoryService.create(user.getId(), TxType.EXPENSE, "Minum");
        postImportExpect422(
                importBody("budgets", List.of(budget(null, second.getId(), "daily", 5L))),
                "id wajib ada");
    }

    @Test
    void budgetUpdateByCategoryKeepsSingleRowAndUpdateByIdMovesCategory() throws Exception {
        Category satu = categoryService.create(user.getId(), TxType.EXPENSE, "Satu");
        Category dua = categoryService.create(user.getId(), TxType.EXPENSE, "Dua");
        UUID budgetId = UUID.randomUUID();

        postImport(
                        importBody(
                                "budgets", List.of(budget(budgetId, satu.getId(), "daily", 10L))),
                        session)
                .andExpect(status().isOk());

        // budget untuk kategori sama → update by category (ID file baru diabaikan)
        postImport(
                        importBody(
                                "budgets",
                                List.of(budget(UUID.randomUUID(), satu.getId(), "monthly", 20L))),
                        session)
                .andExpect(status().isOk());
        assertThat(count("budgets", user.getId())).isEqualTo(1);
        assertThat(
                        jdbcClient
                                .sql("select amount from budgets where id = :id")
                                .param("id", budgetId)
                                .query(Long.class)
                                .single())
                .isEqualTo(20L);

        // ID yang sama milik user tapi kategori lain → update by id
        postImport(
                        importBody("budgets", List.of(budget(budgetId, dua.getId(), "daily", 30L))),
                        session)
                .andExpect(status().isOk());
        assertThat(
                        jdbcClient
                                .sql("select category_id from budgets where id = :id")
                                .param("id", budgetId)
                                .query(UUID.class)
                                .single())
                .isEqualTo(dua.getId());
    }

    @Test
    void budgetIdOwnedByOtherUserIsRejected() throws Exception {
        Category foreignCategory =
                categoryService.create(other.getId(), TxType.EXPENSE, "Milik Lain");
        UUID foreignBudget = UUID.randomUUID();
        jdbcClient
                .sql(
                        """
                        insert into budgets (id, user_id, category_id, period, amount, description,
                                             created_at, updated_at)
                        values (:id, :u, :c, 'daily', 5, '', now(), now())
                        """)
                .param("id", foreignBudget)
                .param("u", other.getId())
                .param("c", foreignCategory.getId())
                .update();

        Category mine = categoryService.create(user.getId(), TxType.EXPENSE, "Punya Saya");
        postImportExpect422(
                importBody("budgets", List.of(budget(foreignBudget, mine.getId(), "daily", 9L))),
                "ID budget sudah dipakai data lain");
    }

    @Test
    void transactionRowsAreValidatedStrictly() throws Exception {
        Category expense = categoryService.create(user.getId(), TxType.EXPENSE, "Makan");
        postImportExpect422(
                importBody(
                        "transactions",
                        List.of(transaction(null, "2026-10-01", "expense", null, null, 5L))),
                "id wajib ada");
        postImportExpect422(
                importBody(
                        "transactions",
                        List.of(transaction(UUID.randomUUID(), null, "expense", null, null, 5L))),
                "tanggal");
        postImportExpect422(
                importBody(
                        "transactions",
                        List.of(
                                transaction(
                                        UUID.randomUUID(), "2026-10-01", "salah", null, null, 5L))),
                "tipe");
        postImportExpect422(
                importBody(
                        "transactions",
                        List.of(
                                transaction(
                                        UUID.randomUUID(), "2026-10-01", null, null, null, 5L))),
                "tipe");
        postImportExpect422(
                importBody(
                        "transactions",
                        List.of(
                                transaction(
                                        UUID.randomUUID(),
                                        "2026-10-01",
                                        "expense",
                                        expense.getId(),
                                        null,
                                        -1L))),
                "amount");
        postImportExpect422(
                importBody(
                        "transactions",
                        List.of(
                                transaction(
                                        UUID.randomUUID(),
                                        "2026-10-01",
                                        "expense",
                                        expense.getId(),
                                        null,
                                        null))),
                "amount");
        postImportExpect422(
                importBody(
                        "transactions",
                        List.of(
                                transaction(
                                        UUID.randomUUID(),
                                        "2026-10-01",
                                        "expense",
                                        expense.getId(),
                                        UUID.randomUUID(),
                                        5L))),
                "merujuk arsip");
    }

    @Test
    void incomeTransactionWithExpenseCategoryIsRejected() throws Exception {
        Category expense = categoryService.create(user.getId(), TxType.EXPENSE, "Makan");
        postImportExpect422(
                importBody(
                        "transactions",
                        List.of(
                                transaction(
                                        UUID.randomUUID(),
                                        "2026-10-01",
                                        "income",
                                        expense.getId(),
                                        null,
                                        5000L))),
                "tipe kategori tidak sesuai");
    }

    @Test
    void uncategorizedAndNullDescriptionWork() throws Exception {
        UUID txId = UUID.randomUUID();
        postImport(
                        importBody(
                                "transactions",
                                List.of(
                                        transaction(
                                                txId, "2026-10-01", "expense", null, null, 1234L))),
                        session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions").value(1));
        assertThat(
                        jdbcClient
                                .sql(
                                        "select coalesce(description,'NULL') from transactions where id = :id")
                                .param("id", txId)
                                .query(String.class)
                                .single())
                .isEmpty();
    }

    @Test
    void transactionsCanReferenceFileAndPreexistingClosings() throws Exception {
        UUID fileClosing = UUID.randomUUID();
        UUID txInFile = UUID.randomUUID();
        postImport(
                        importBody(
                                "closings",
                                List.of(closing(fileClosing, "2026-10-01", "2026-10-31", "a.xlsx")),
                                "transactions",
                                List.of(
                                        transaction(
                                                txInFile,
                                                "2026-10-02",
                                                "expense",
                                                null,
                                                fileClosing,
                                                999L))),
                        session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions").value(1))
                .andExpect(jsonPath("$.closings").value(1));

        UUID txPreexisting = UUID.randomUUID();
        postImport(
                        importBody(
                                "transactions",
                                List.of(
                                        transaction(
                                                txPreexisting,
                                                "2026-10-03",
                                                "expense",
                                                null,
                                                fileClosing,
                                                888L))),
                        session)
                .andExpect(status().isOk());

        assertThat(
                        jdbcClient
                                .sql("select closing_id from transactions where id = :id")
                                .param("id", txPreexisting)
                                .query(UUID.class)
                                .single())
                .isEqualTo(fileClosing);
    }

    @Test
    void transactionIdOwnedByOtherUserIsRejected() throws Exception {
        UUID foreignTx = UUID.randomUUID();
        jdbcClient
                .sql(
                        """
                        insert into transactions (id, user_id, date, type, description, amount)
                        values (:id, :u, date '2026-10-01', 'expense', 'milik lain', 5)
                        """)
                .param("id", foreignTx)
                .param("u", other.getId())
                .update();

        postImportExpect422(
                importBody(
                        "transactions",
                        List.of(transaction(foreignTx, "2026-10-05", "expense", null, null, 7L))),
                "ID transaksi sudah dipakai data lain");
    }

    @Test
    void exportThenImportRoundTripRebuildsSecondUserBriefcase() throws Exception {
        Category makanan = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        UUID closingId = UUID.randomUUID();
        postImport(
                        importBody(
                                "closings",
                                List.of(
                                        withTotals(
                                                closing(
                                                        closingId,
                                                        "2026-10-01",
                                                        "2026-10-31",
                                                        "ok.xlsx"),
                                                10L,
                                                20L))),
                        session)
                .andExpect(status().isOk());
        jdbcClient
                .sql(
                        """
                        insert into budgets (id, user_id, category_id, period, amount, description,
                                             created_at, updated_at)
                        values (:id, :u, :c, 'monthly', 100000, 'makan', now(), now())
                        """)
                .param("id", UUID.randomUUID())
                .param("u", user.getId())
                .param("c", makanan.getId())
                .update();
        jdbcClient
                .sql("update users set display_name = 'Pemilik Asli' where id = :u")
                .param("u", user.getId())
                .update();

        byte[] backup =
                mockMvc.perform(get("/api/v1/export/backup.json").cookie(session))
                        .andExpect(status().isOk())
                        .andExpect(
                                MockMvcResultMatchers.header()
                                        .string(
                                                "Content-Disposition",
                                                org.hamcrest.Matchers.containsString("backup_")))
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        String body = new String(backup, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("Makanan").contains("\"version\":1");

        // ID backup global-unique: kosongkan brankas pemilik dulu, baru import ke user kedua
        jdbcClient.sql("delete from budgets where user_id = :u").param("u", user.getId()).update();
        jdbcClient
                .sql("delete from transactions where user_id = :u")
                .param("u", user.getId())
                .update();
        jdbcClient.sql("delete from closings where user_id = :u").param("u", user.getId()).update();
        jdbcClient
                .sql("delete from categories where user_id = :u")
                .param("u", user.getId())
                .update();

        postImport(body, otherSession)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").value(1))
                .andExpect(jsonPath("$.closings").value(1))
                .andExpect(jsonPath("$.budgets").value(1));

        assertThat(count("categories", other.getId())).isEqualTo(1);
        assertThat(count("closings", other.getId())).isEqualTo(1);
        assertThat(count("budgets", other.getId())).isEqualTo(1);
        assertThat(
                        jdbcClient
                                .sql("select display_name, timezone from users where id = :u")
                                .param("u", other.getId())
                                .query((rs, n) -> rs.getString(1) + "|" + rs.getString(2))
                                .single())
                .isEqualTo("Pemilik Asli|Asia/Jakarta");
    }

    @Test
    void malformedImportBodyIsRejected() throws Exception {
        postImport("{\"rusak", session)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void profilePartialFieldsOnlyTouchProvidedColumns() throws Exception {
        postImport(
                        importBody(
                                "user",
                                java.util.Collections.singletonMap("displayName", "Sebagian")),
                        session)
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me").cookie(session))
                .andExpect(jsonPath("$.displayName").value("Sebagian"))
                .andExpect(jsonPath("$.timezone").value("Asia/Jakarta"))
                .andExpect(jsonPath("$.dailyLimit").doesNotExist());
    }

    @Test
    void categoryRowWithoutArchivedKeyDefaultsFalse() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id.toString());
        row.put("type", "expense");
        row.put("name", "Tanpa Flag");
        postImport(importBody("categories", List.of(row)), session).andExpect(status().isOk());
        assertThat(
                        jdbcClient
                                .sql("select archived from categories where id = :id")
                                .param("id", id)
                                .query(Boolean.class)
                                .single())
                .isFalse();
    }

    @Test
    void closingWithoutPeriodEndIsRejected() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID().toString());
        row.put("periodStart", "2026-10-01");
        row.put("fileName", "x.xlsx");
        postImport(importBody("closings", List.of(row)), session)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(
                        result ->
                                assertThat(result.getResponse().getContentAsString())
                                        .contains("periode"));
    }

    @Test
    void budgetWithoutDescriptionUsesEmptyString() throws Exception {
        Category expense = categoryService.create(user.getId(), TxType.EXPENSE, "Makan");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID().toString());
        row.put("categoryId", expense.getId().toString());
        row.put("period", "monthly");
        row.put("amount", 5000L);
        postImport(importBody("budgets", List.of(row)), session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgets").value(1));
        assertThat(
                        jdbcClient
                                .sql("select description from budgets where category_id = :c")
                                .param("c", expense.getId())
                                .query(String.class)
                                .single())
                .isEmpty();
    }
}
