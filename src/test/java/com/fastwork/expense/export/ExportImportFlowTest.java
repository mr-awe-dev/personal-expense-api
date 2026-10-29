package com.fastwork.expense.export;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fastwork.expense.PostgresIntegrationTest;
import com.fastwork.expense.category.Category;
import com.fastwork.expense.category.CategoryService;
import com.fastwork.expense.closing.ClosePeriodRequest;
import com.fastwork.expense.closing.ClosingService;
import com.fastwork.expense.common.TxType;
import com.fastwork.expense.transaction.TransactionService;
import com.fastwork.expense.transaction.UpsertTransactionRequest;
import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRepository;
import com.fastwork.expense.user.UserService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExportImportFlowTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "kbackup-12345678";
    private static final String OTHER_PASSWORD = "kbackup-lainnya-7777777";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String SESSION_COOKIE = "PET_SESSION";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;
    @Autowired private CategoryService categoryService;
    @Autowired private TransactionService transactionService;
    @Autowired private ClosingService closingService;
    @Autowired private JdbcClient jdbcClient;

    private User user;
    private Cookie session;
    private LocalDate d1;
    private LocalDate d2;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();
        user = userService.createUser("Pemilik@Example.com ", PASSWORD);
        session = login("pemilik@example.com", PASSWORD);
        d1 = LocalDate.now(ZoneId.of(user.getTimezone())).minusDays(5);
        d2 = LocalDate.now(ZoneId.of(user.getTimezone())).minusDays(4);
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/export/backup.json")).andExpect(status().isUnauthorized());
        Cookie csrf = csrfCookie();
        mockMvc.perform(
                        post("/api/v1/import")
                                .cookie(csrf)
                                .header(CSRF_HEADER, csrf.getValue())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exportContainsFullBriefcaseIncludingArchived() throws Exception {
        long dailyLimit = 50000L;
        mockMvc.perform(
                        authorized(patch("/api/v1/me"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("dailyLimit", dailyLimit))))
                .andExpect(status().isOk());

        Category makanan = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        Category gaji = categoryService.create(user.getId(), TxType.INCOME, "Gaji");
        seedExpense(d1, "Kopi", 30000L, makanan.getId());
        seedExpense(d2, "Tanpa kategori", 20000L, null);
        seedIncome(d1, "Freelance", 90000L, gaji.getId());
        categoryService.update(user.getId(), makanan.getId(), null, true); // arsip kategori
        closingService.close(user.getId(), new ClosePeriodRequest(d1, d1));

        MvcResult result =
                mockMvc.perform(get("/api/v1/export/backup.json").cookie(session))
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(
                                header().string(
                                                "Content-Disposition",
                                                org.hamcrest.Matchers.containsString("backup_")))
                        .andExpect(jsonPath("$.version").value(1))
                        .andExpect(jsonPath("$.user.dailyLimit").value(dailyLimit))
                        .andExpect(jsonPath("$.categories.length()").value(2))
                        .andExpect(jsonPath("$.transactions.length()").value(3))
                        .andExpect(jsonPath("$.closings.length()").value(1))
                        .andExpect(jsonPath("$.closings[0].fileName").exists())
                        .andReturn();
        Long withClosing =
                jdbcClient
                        .sql("select count(*) from transactions where closing_id is not null")
                        .query((rs, rowNum) -> rs.getLong(1))
                        .list()
                        .getFirst();
        org.junit.jupiter.api.Assertions.assertEquals(2L, withClosing);
    }

    @Test
    void importRoundTripRestoresExactlyOnce() throws Exception {
        Category makanan = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        Category gaji = categoryService.create(user.getId(), TxType.INCOME, "Gaji");
        seedExpense(d1, "Kopi", 30000L, makanan.getId());
        seedIncome(d1, "Gaji", 90000L, gaji.getId());
        closingService.close(user.getId(), new ClosePeriodRequest(d1, d1));

        byte[] backup =
                mockMvc.perform(get("/api/v1/export/backup.json").cookie(session))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();

        wipeUserData();
        mockMvc.perform(
                        authorized(post("/api/v1/import"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(backup))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").value(2))
                .andExpect(jsonPath("$.transactions").value(2))
                .andExpect(jsonPath("$.budgets").value(0))
                .andExpect(jsonPath("$.closings").value(1));

        assertCounts(2, 2, 0, 1);

        // Re-import idempotent: tidak ada duplikat, angka tetap sama.
        mockMvc.perform(
                        authorized(post("/api/v1/import"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(backup))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").value(0))
                .andExpect(jsonPath("$.closings").value(1));
        assertCounts(2, 2, 0, 1);
    }

    @Test
    void importMergesSameNameCategoriesRemappingTransactions() throws Exception {
        Category existing = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        Map<String, Object> backup =
                Map.of(
                        "version",
                        1,
                        "categories",
                        java.util.List.of(
                                Map.of(
                                        "id",
                                        UUID.randomUUID().toString(),
                                        "type",
                                        "expense",
                                        "name",
                                        "MAKANAN",
                                        "archived",
                                        false)),
                        "transactions",
                        java.util.List.of(
                                Map.of(
                                        "id",
                                        UUID.randomUUID().toString(),
                                        "date",
                                        d1.toString(),
                                        "type",
                                        "expense",
                                        "categoryId",
                                        existing.getId().toString(),
                                        "description",
                                        "Teh",
                                        "amount",
                                        12000L)),
                        "budgets",
                        java.util.List.of(),
                        "closings",
                        java.util.List.of());
        mockMvc.perform(
                        authorized(post("/api/v1/import"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(backup)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").value(0))
                .andExpect(jsonPath("$.transactions").value(1));

        Long duplicates =
                jdbcClient
                        .sql(
                                "select count(*) from categories"
                                        + " where user_id = :userId and name_key = :nameKey")
                        .param("userId", user.getId())
                        .param("nameKey", "makanan")
                        .query((rs, rowNum) -> rs.getLong(1))
                        .list()
                        .getFirst();
        org.junit.jupiter.api.Assertions.assertEquals(1L, duplicates);
        Long relinked =
                jdbcClient
                        .sql(
                                "select count(*) from transactions"
                                        + " where user_id = :userId and category_id = :categoryId")
                        .param("userId", user.getId())
                        .param("categoryId", existing.getId())
                        .query((rs, rowNum) -> rs.getLong(1))
                        .list()
                        .getFirst();
        org.junit.jupiter.api.Assertions.assertEquals(1L, relinked);
    }

    @Test
    void importRejectsUnsupportedVersionAndMalformedBody() throws Exception {
        mockMvc.perform(
                        authorized(post("/api/v1/import"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"version\":2}"))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(
                        authorized(post("/api/v1/import"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{bukan json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void importRejectsForeignTransactionIds() throws Exception {
        userService.createUser("Lainnya@Example.com ", OTHER_PASSWORD);
        Cookie otherSession = login("lainnya@example.com", OTHER_PASSWORD);
        User other = userRepository.findByEmail("lainnya@example.com").orElseThrow();
        Category otherCat = categoryService.create(other.getId(), TxType.EXPENSE, "Transport");
        UUID foreignTx =
                transactionService
                        .upsert(
                                other.getId(),
                                UUID.randomUUID(),
                                new UpsertTransactionRequest(
                                        d1, TxType.EXPENSE, otherCat.getId(), "Bus", 8000L))
                        .id();

        Map<String, Object> backup =
                Map.of(
                        "version",
                        1,
                        "categories",
                        java.util.List.of(),
                        "transactions",
                        java.util.List.of(
                                Map.of(
                                        "id",
                                        foreignTx.toString(),
                                        "date",
                                        d1.toString(),
                                        "type",
                                        "expense",
                                        "description",
                                        "Bus",
                                        "amount",
                                        8000L)),
                        "budgets",
                        java.util.List.of(),
                        "closings",
                        java.util.List.of());

        mockMvc.perform(
                        authorized(post("/api/v1/import"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(backup)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void importRollsBackFullyOnBrokenRow() throws Exception {
        Map<String, Object> backup =
                Map.of(
                        "version",
                        1,
                        "categories",
                        java.util.List.of(
                                Map.of(
                                        "id",
                                        UUID.randomUUID().toString(),
                                        "type",
                                        "expense",
                                        "name",
                                        "Sembako",
                                        "archived",
                                        false)),
                        "transactions",
                        java.util.List.of(
                                Map.of(
                                        "id",
                                        UUID.randomUUID().toString(),
                                        "date",
                                        d1.toString(),
                                        "type",
                                        "expense",
                                        "description",
                                        "Rusak",
                                        "amount",
                                        0L)),
                        "budgets",
                        java.util.List.of(),
                        "closings",
                        java.util.List.of());

        mockMvc.perform(
                        authorized(post("/api/v1/import"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(backup)))
                .andExpect(status().isUnprocessableEntity());

        Long categories =
                jdbcClient
                        .sql("select count(*) from categories where user_id = :userId")
                        .param("userId", user.getId())
                        .query((rs, rowNum) -> rs.getLong(1))
                        .list()
                        .getFirst();
        org.junit.jupiter.api.Assertions.assertEquals(0L, categories);
    }

    private void assertCounts(int categories, int transactions, int budgets, int closings) {
        org.junit.jupiter.api.Assertions.assertEquals(categories, countOf("categories"));
        org.junit.jupiter.api.Assertions.assertEquals(transactions, countOf("transactions"));
        org.junit.jupiter.api.Assertions.assertEquals(budgets, countOf("budgets"));
        org.junit.jupiter.api.Assertions.assertEquals(closings, countOf("closings"));
    }

    private int countOf(String table) {
        Long value =
                jdbcClient
                        .sql("select count(*) from " + table + " where user_id = :userId")
                        .param("userId", user.getId())
                        .query((rs, rowNum) -> rs.getLong(1))
                        .list()
                        .getFirst();
        return value.intValue();
    }

    private void wipeUserData() {
        for (String table : new String[] {"transactions", "budgets", "closings", "categories"}) {
            jdbcClient
                    .sql("delete from " + table + " where user_id = :userId")
                    .param("userId", user.getId())
                    .update();
        }
    }

    private UUID seedExpense(LocalDate date, String description, long amount, UUID categoryId) {
        return transactionService
                .upsert(
                        user.getId(),
                        UUID.randomUUID(),
                        new UpsertTransactionRequest(
                                date, TxType.EXPENSE, categoryId, description, amount))
                .id();
    }

    private UUID seedIncome(LocalDate date, String description, long amount, UUID sourceId) {
        return transactionService
                .upsert(
                        user.getId(),
                        UUID.randomUUID(),
                        new UpsertTransactionRequest(
                                date, TxType.INCOME, sourceId, description, amount))
                .id();
    }

    private MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, Cookie activeSession) throws Exception {
        Cookie csrf = csrfCookie();
        return builder.cookie(activeSession, csrf).header(CSRF_HEADER, csrf.getValue());
    }

    private Cookie login(String email, String password) throws Exception {
        Cookie csrf = csrfCookie();
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .cookie(csrf)
                                        .header(CSRF_HEADER, csrf.getValue())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json(Map.of("email", email, "password", password))))
                        .andExpect(status().isOk())
                        .andReturn();
        for (Cookie cookie : result.getResponse().getCookies()) {
            if (SESSION_COOKIE.equals(cookie.getName())) {
                return cookie;
            }
        }
        throw new IllegalStateException("Session cookie tidak dibuat");
    }

    private Cookie csrfCookie() throws Exception {
        MvcResult probe = mockMvc.perform(get("/api/v1/me")).andReturn();
        for (Cookie cookie : probe.getResponse().getCookies()) {
            if (CSRF_COOKIE.equals(cookie.getName())) {
                return cookie;
            }
        }
        throw new IllegalStateException("Cookie " + CSRF_COOKIE + " tidak dibuat");
    }

    private String json(Map<String, Object> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }
}
