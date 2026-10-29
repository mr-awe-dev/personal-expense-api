package com.fastwork.expense.closing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import com.fastwork.expense.budget.BudgetPeriod;
import com.fastwork.expense.budget.BudgetService;
import com.fastwork.expense.budget.UpsertBudgetRequest;
import com.fastwork.expense.category.Category;
import com.fastwork.expense.category.CategoryService;
import com.fastwork.expense.common.TxType;
import com.fastwork.expense.transaction.TransactionService;
import com.fastwork.expense.transaction.UpsertTransactionRequest;
import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRepository;
import com.fastwork.expense.user.UserService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClosingFlowTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "kclosing-12345678";
    private static final String OTHER_PASSWORD = "kclosing-lainnya-777777";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String SESSION_COOKIE = "PET_SESSION";
    private static final DateTimeFormatter COMPACT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;
    @Autowired private CategoryService categoryService;
    @Autowired private BudgetService budgetService;
    @Autowired private TransactionService transactionService;
    @Autowired private JdbcClient jdbcClient;

    private User user;
    private Cookie session;
    private LocalDate today;
    private LocalDate d1;
    private LocalDate d2;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();
        user = userService.createUser("Pemilik@Example.com ", PASSWORD);
        session = login("pemilik@example.com", PASSWORD);
        today = LocalDate.now(ZoneId.of(user.getTimezone()));
        d1 = today.minusDays(5);
        d2 = today.minusDays(4);
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/closings")).andExpect(status().isUnauthorized());
    }

    @Test
    void previewDefaultsToFirstAndLastOpenTransaction() throws Exception {
        Category makanan = createExpenseCategory("Makanan");
        Category gaji = categoryService.create(user.getId(), TxType.INCOME, "Gaji");
        seedExpense(d1, "Kopi", 30000L, makanan.getId());
        seedExpense(d2, "Tanpa kategori", 20000L, null);
        seedIncome(today, "Freelance", 50000L, gaji.getId());

        mockMvc.perform(get("/api/v1/closings/preview").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(d1.toString()))
                .andExpect(jsonPath("$.to").value(today.toString()))
                .andExpect(jsonPath("$.txCount").value(3))
                .andExpect(jsonPath("$.totalIncome").value(50000))
                .andExpect(jsonPath("$.totalExpense").value(50000))
                .andExpect(jsonPath("$.net").value(0));
    }

    @Test
    void previewWithExplicitRangeCoversOnlyThatRange() throws Exception {
        Category makanan = createExpenseCategory("Makanan");
        seedExpense(d1, "Kopi", 30000L, makanan.getId());
        seedIncome(d2, "Gaji", 90000L, createIncomeCategory().getId());

        mockMvc.perform(
                        get("/api/v1/closings/preview")
                                .cookie(session)
                                .param("from", d1.toString())
                                .param("to", d2.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(d1.toString()))
                .andExpect(jsonPath("$.to").value(d2.toString()))
                .andExpect(jsonPath("$.txCount").value(2))
                .andExpect(jsonPath("$.totalExpense").value(30000))
                .andExpect(jsonPath("$.totalIncome").value(90000))
                .andExpect(jsonPath("$.net").value(60000));
    }

    @Test
    void previewWithoutTransactionsDefaultsToToday() throws Exception {
        mockMvc.perform(get("/api/v1/closings/preview").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(today.toString()))
                .andExpect(jsonPath("$.to").value(today.toString()))
                .andExpect(jsonPath("$.txCount").value(0))
                .andExpect(jsonPath("$.totalIncome").value(0))
                .andExpect(jsonPath("$.totalExpense").value(0));
    }

    @Test
    void previewRejectsReversedRange() throws Exception {
        mockMvc.perform(
                        get("/api/v1/closings/preview")
                                .cookie(session)
                                .param("from", today.toString())
                                .param("to", d2.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RANGE"));
    }

    @Test
    void closeArchivesTransactionsAndRemovesThemFromTheOpenList() throws Exception {
        Category makanan = createExpenseCategory("Makanan");
        budgetService.upsert(
                user.getId(),
                makanan.getId(),
                new UpsertBudgetRequest(BudgetPeriod.DAILY, 50000L, null));
        seedExpense(d1, "Kopi", 30000L, makanan.getId());
        seedIncome(d2, "Gaji", 120000L, createIncomeCategory().getId());

        MvcResult result =
                mockMvc.perform(
                                authorized(post("/api/v1/closings"), session)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json(
                                                        Map.of(
                                                                "from", d1.toString(),
                                                                "to", d2.toString()))))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.periodStart").value(d1.toString()))
                        .andExpect(jsonPath("$.periodEnd").value(d2.toString()))
                        .andExpect(jsonPath("$.totalIncome").value(120000))
                        .andExpect(jsonPath("$.totalExpense").value(30000))
                        .andExpect(jsonPath("$.net").value(90000))
                        .andExpect(jsonPath("$.txCount").value(2))
                        .andExpect(jsonPath("$.fileName").value(fileName(d1, d2)))
                        .andReturn();
        String closingId =
                objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/transactions").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        mockMvc.perform(get("/api/v1/closings").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(closingId))
                .andExpect(jsonPath("$[0].txCount").value(2));
    }

    @Test
    void closeRejectsReversedRangeAndEmptyRange() throws Exception {
        mockMvc.perform(
                        authorized(post("/api/v1/closings"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json(
                                                Map.of(
                                                        "from", today.toString(),
                                                        "to", d2.toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RANGE"));

        mockMvc.perform(
                        authorized(post("/api/v1/closings"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json(
                                                Map.of(
                                                        "from", d1.toString(),
                                                        "to", d2.toString()))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void closeRequiresBothDates() throws Exception {
        mockMvc.perform(
                        authorized(post("/api/v1/closings"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"from\":\"" + d1 + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void listOrdersNewestClosureFirst() throws Exception {
        seedExpense(d1, "Kopi", 10000L, null);
        close(d1, d2);

        LocalDate old1 = today.minusDays(45);
        LocalDate old2 = today.minusDays(40);
        seedExpense(old1, "Bensin", 20000L, null);
        close(old1, old2);

        mockMvc.perform(get("/api/v1/closings").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].periodStart").value(old1.toString()))
                .andExpect(jsonPath("$[1].periodStart").value(d1.toString()));
    }

    @Test
    void exportStreamsExcelWithTotalsRowsSubtotalAndBudget() throws Exception {
        Category makanan = createExpenseCategory("Makanan");
        budgetService.upsert(
                user.getId(),
                makanan.getId(),
                new UpsertBudgetRequest(BudgetPeriod.DAILY, 5000L, null));
        seedExpense(d1, "Kopi", 3000L, makanan.getId());
        seedExpense(d2, "=SUM(A1)", 7000L, makanan.getId());
        seedIncome(d2, "Gaji harian", 100000L, createIncomeCategory().getId());
        String closingId = close(d1, d2);

        MvcResult result =
                mockMvc.perform(
                                get("/api/v1/closings/" + closingId + "/export.xlsx")
                                        .cookie(session))
                        .andExpect(status().isOk())
                        .andExpect(
                                res ->
                                        org.junit.jupiter.api.Assertions.assertTrue(
                                                res.getResponse()
                                                        .getHeader("Content-Disposition")
                                                        .contains(
                                                                "closing_"
                                                                        + d1.format(COMPACT)
                                                                        + "-"
                                                                        + d2.format(COMPACT)
                                                                        + ".xlsx")))
                        .andReturn();

        byte[] bytes = result.getResponse().getContentAsByteArray();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            XSSFSheet sheet = workbook.getSheet("Arsip");
            org.junit.jupiter.api.Assertions.assertNotNull(sheet);
            Row header = sheet.getRow(5);
            org.junit.jupiter.api.Assertions.assertEquals(
                    "Tanggal", header.getCell(0).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals(
                    "Budget", header.getCell(5).getStringCellValue());
            Row totalsRow = sheet.getRow(2);
            org.junit.jupiter.api.Assertions.assertEquals(
                    10000, totalsRow.getCell(1).getNumericCellValue(), 0.0001);
            Row firstTx = sheet.getRow(6);
            org.junit.jupiter.api.Assertions.assertEquals(
                    3000, firstTx.getCell(4).getNumericCellValue(), 0.0001);
            Row secondTx = sheet.getRow(7);
            org.junit.jupiter.api.Assertions.assertEquals(
                    "'=SUM(A1)", secondTx.getCell(3).getStringCellValue());
            Row incomeTx = sheet.getRow(8);
            org.junit.jupiter.api.Assertions.assertEquals(
                    100000, incomeTx.getCell(4).getNumericCellValue(), 0.0001);
            Row expenseSubtotal = sheet.getRow(9);
            org.junit.jupiter.api.Assertions.assertEquals(
                    "Makanan", expenseSubtotal.getCell(2).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals(
                    "Subtotal", expenseSubtotal.getCell(3).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals(
                    10000, expenseSubtotal.getCell(4).getNumericCellValue(), 0.0001);
            org.junit.jupiter.api.Assertions.assertEquals(
                    10000, expenseSubtotal.getCell(5).getNumericCellValue(), 0.0001);
            org.junit.jupiter.api.Assertions.assertEquals(
                    0, expenseSubtotal.getCell(6).getNumericCellValue(), 0.0001);
            Row incomeSubtotal = sheet.getRow(10);
            org.junit.jupiter.api.Assertions.assertEquals(
                    100000, incomeSubtotal.getCell(4).getNumericCellValue(), 0.0001);
            org.junit.jupiter.api.Assertions.assertNull(incomeSubtotal.getCell(5));
            Row grand = sheet.getRow(11);
            org.junit.jupiter.api.Assertions.assertEquals(
                    "Grand Total", grand.getCell(3).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals(
                    110000, grand.getCell(4).getNumericCellValue(), 0.0001);
            org.junit.jupiter.api.Assertions.assertEquals(
                    10000, grand.getCell(5).getNumericCellValue(), 0.0001);
            org.junit.jupiter.api.Assertions.assertEquals(
                    0, grand.getCell(6).getNumericCellValue(), 0.0001);
        }
    }

    @Test
    void exportRejectsForeignOrMissingClosing() throws Exception {
        String foreignId = createForeignClosing();
        mockMvc.perform(get("/api/v1/closings/" + foreignId + "/export.xlsx").cookie(session))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        get("/api/v1/closings/" + UUID.randomUUID() + "/export.xlsx")
                                .cookie(session))
                .andExpect(status().isNotFound());
    }

    @Test
    void reopenRestoresTransactionsAndDeletesLog() throws Exception {
        Category makanan = createExpenseCategory("Makanan");
        seedExpense(d1, "Kopi", 30000L, makanan.getId());
        String closingId = close(d1, d2);

        mockMvc.perform(authorized(post("/api/v1/closings/" + closingId + "/reopen"), session))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/transactions").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));

        mockMvc.perform(get("/api/v1/closings").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/v1/closings/" + closingId + "/export.xlsx").cookie(session))
                .andExpect(status().isNotFound());
    }

    @Test
    void reopenRejectsForeignClosing() throws Exception {
        String foreignId = createForeignClosing();
        mockMvc.perform(authorized(post("/api/v1/closings/" + foreignId + "/reopen"), session))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteDataRequiresConfirmationAndDeletesPermanently() throws Exception {
        Category makanan = createExpenseCategory("Makanan");
        seedExpense(d1, "Kopi", 30000L, makanan.getId());
        String closingId = close(d1, d2);

        mockMvc.perform(
                        authorized(delete("/api/v1/closings/" + closingId + "/data"), session)
                                .param("confirm", "salah"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONFIRM_MISMATCH"));

        mockMvc.perform(
                        authorized(delete("/api/v1/closings/" + closingId + "/data"), session)
                                .param("confirm", "HAPUS"))
                .andExpect(status().isNoContent());

        Long remaining =
                jdbcClient
                        .sql("select count(*) from transactions where closing_id = :closingId")
                        .param("closingId", UUID.fromString(closingId))
                        .query((rs, rowNum) -> rs.getLong(1))
                        .list()
                        .getFirst();
        org.junit.jupiter.api.Assertions.assertEquals(0L, remaining);

        mockMvc.perform(get("/api/v1/closings").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/v1/closings/" + closingId + "/export.xlsx").cookie(session))
                .andExpect(status().isOk());
    }

    private Category createExpenseCategory(String name) {
        return categoryService.create(user.getId(), TxType.EXPENSE, name);
    }

    private Category createIncomeCategory() {
        return categoryService.create(user.getId(), TxType.INCOME, "Gaji");
    }

    private String close(LocalDate from, LocalDate to) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                authorized(post("/api/v1/closings"), session)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json(
                                                        Map.of(
                                                                "from", from.toString(),
                                                                "to", to.toString()))))
                        .andExpect(status().isCreated())
                        .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createForeignClosing() throws Exception {
        userService.createUser("Lainnya@Example.com ", OTHER_PASSWORD);
        Cookie otherSession = login("lainnya@example.com", OTHER_PASSWORD);
        User other = userRepository.findByEmail("lainnya@example.com").orElseThrow();
        Category otherCat = categoryService.create(other.getId(), TxType.EXPENSE, "Transport");
        transactionService.upsert(
                other.getId(),
                UUID.randomUUID(),
                new UpsertTransactionRequest(
                        d1, TxType.EXPENSE, otherCat.getId(), "Naik bus", 8000L));
        MvcResult result =
                mockMvc.perform(
                                authorized(post("/api/v1/closings"), otherSession)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json(
                                                        Map.of(
                                                                "from", d1.toString(),
                                                                "to", d2.toString()))))
                        .andExpect(status().isCreated())
                        .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String fileName(LocalDate from, LocalDate to) {
        return "closing_" + from.format(COMPACT) + "-" + to.format(COMPACT) + ".xlsx";
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
