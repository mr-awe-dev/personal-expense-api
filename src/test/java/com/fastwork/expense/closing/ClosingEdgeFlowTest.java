package com.fastwork.expense.closing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneOffset;
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

import com.fastwork.expense.WebFlowTestBase;
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

/** Rentang preview satu sisi, timezone default tidak valid, dan export dengan budget MONTHLY. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClosingEdgeFlowTest extends WebFlowTestBase {

    @Autowired private UserRepository userRepository;
    @Autowired private CategoryService categoryService;
    @Autowired private TransactionService transactionService;
    @Autowired private BudgetService budgetService;
    @Autowired private JdbcClient jdbcClient;

    private User user;
    private Cookie session;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();
        user = userService.createUser("pemilik@example.com", PASSWORD);
        session = login("pemilik@example.com");
    }

    private void tx(String date, Category category, long amount) {
        transactionService.upsert(
                user.getId(),
                UUID.randomUUID(),
                new UpsertTransactionRequest(
                        LocalDate.parse(date),
                        TxType.EXPENSE,
                        category == null ? null : category.getId(),
                        "belanja " + date,
                        amount));
    }

    @Test
    void previewAcceptsOnlyFromAndDefaultsToLastOpenDate() throws Exception {
        Category makanan = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        tx("2026-10-03", makanan, 10_000L);
        tx("2026-10-09", makanan, 12_000L);

        mockMvc.perform(
                        get("/api/v1/closings/preview")
                                .cookie(session)
                                .param("from", "2026-10-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.to").value("2026-10-09"))
                .andExpect(jsonPath("$.txCount").value(2));

        mockMvc.perform(
                        get("/api/v1/closings/preview").cookie(session).param("to", "2026-10-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-10-03"));
    }

    @Test
    void previewWithInvalidTimezoneAndNoRowsFallsBackToUtcToday() throws Exception {
        jdbcClient
                .sql("update users set timezone = 'Bukan/Zona' where id = :id")
                .param("id", user.getId())
                .update();

        mockMvc.perform(get("/api/v1/closings/preview").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(LocalDate.now(ZoneOffset.UTC).toString()))
                .andExpect(jsonPath("$.to").value(LocalDate.now(ZoneOffset.UTC).toString()))
                .andExpect(jsonPath("$.txCount").value(0));
    }

    @Test
    void exportCoversMonthlyBudgetScalingAndUncategorizedRows() throws Exception {
        Category makanan = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        Category tanpaBudget = categoryService.create(user.getId(), TxType.EXPENSE, "Lainnya");
        budgetService.upsert(
                user.getId(),
                makanan.getId(),
                new UpsertBudgetRequest(BudgetPeriod.MONTHLY, 500_000L, "makan"));
        tx("2026-10-04", makanan, 120_000L);
        tx("2026-10-05", tanpaBudget, 45_000L);
        tx("2026-10-06", null, 30_000L);

        String body = close("2026-10-01", "2026-10-31");
        String id = objectMapper.readTree(body).get("id").asText();

        byte[] xlsx =
                mockMvc.perform(
                                get("/api/v1/closings/" + id + "/export.xlsx").cookie(session))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        assertThat(xlsx).isNotEmpty();

        try (org.apache.poi.ss.usermodel.Workbook workbook =
                org.apache.poi.ss.usermodel.WorkbookFactory.create(
                        new java.io.ByteArrayInputStream(xlsx))) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheet("Arsip");
            boolean monthlyBudgetRow = false;
            boolean uncategorizedSubtotal = false;
            for (org.apache.poi.ss.usermodel.Row row : sheet) {
                org.apache.poi.ss.usermodel.Cell name = row.getCell(2);
                org.apache.poi.ss.usermodel.Cell label = row.getCell(3);
                if (name == null || label == null || !"Subtotal".equals(label.getStringCellValue())) {
                    continue;
                }
                if ("Makanan".equals(name.getStringCellValue())) {
                    assertThat(row.getCell(5).getNumericCellValue()).isEqualTo(500_000d);
                    assertThat(row.getCell(6).getNumericCellValue()).isEqualTo(120_000d - 500_000d);
                    monthlyBudgetRow = true;
                }
                if ("-".equals(name.getStringCellValue())) {
                    assertThat(row.getCell(5)).isNull();
                    uncategorizedSubtotal = true;
                }
            }
            assertThat(monthlyBudgetRow).isTrue();
            assertThat(uncategorizedSubtotal).isTrue();
        }
    }

    @Test
    void reopenAndDeleteDataRoundTripForUnclosedEdge() throws Exception {
        Category makanan = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        tx("2026-10-05", makanan, 10_000L);
        String body = close("2026-10-01", "2026-10-31");
        String id = objectMapper.readTree(body).get("id").asText();

        mockMvc.perform(
                        authorized(
                                post("/api/v1/closings/" + id + "/reopen"), session))
                .andExpect(status().isNoContent());
        assertThat(jdbcClient
                        .sql("select count(*) from transactions where closing_id is null and user_id = :u")
                        .param("u", user.getId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    private String close(String from, String to) throws Exception {
        return mockMvc.perform(
                        authorized(post("/api/v1/closings"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("from", from, "to", to))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
