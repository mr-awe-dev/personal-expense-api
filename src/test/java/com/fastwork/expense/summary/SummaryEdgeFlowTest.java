package com.fastwork.expense.summary;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

/** Zona waktu tidak valid pada summary + kelompok tabel tanpa budget (budgetFor → null). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SummaryEdgeFlowTest extends WebFlowTestBase {

    @Autowired private UserRepository userRepository;
    @Autowired private CategoryService categoryService;
    @Autowired private TransactionService transactionService;
    @Autowired private BudgetService budgetService;
    @Autowired private JdbcClient jdbcClient;

    private User user;
    private Cookie session;
    private Category adaBudget;
    private Category tanpaBudget;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();
        user = userService.createUser("pemilik@example.com", PASSWORD);
        session = login("pemilik@example.com");
        adaBudget = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        tanpaBudget = categoryService.create(user.getId(), TxType.EXPENSE, "Transportasi");
        transactionService.upsert(
                user.getId(),
                UUID.randomUUID(),
                new UpsertTransactionRequest(
                        LocalDate.of(2026, 10, 10),
                        TxType.EXPENSE,
                        adaBudget.getId(),
                        "belanja makan",
                        30_000L));
        transactionService.upsert(
                user.getId(),
                UUID.randomUUID(),
                new UpsertTransactionRequest(
                        LocalDate.of(2026, 10, 11),
                        TxType.EXPENSE,
                        tanpaBudget.getId(),
                        "ojek",
                        12_000L));
        budgetService.upsert(
                user.getId(),
                adaBudget.getId(),
                new UpsertBudgetRequest(BudgetPeriod.DAILY, 20_000L, "makan"));
    }

    @Test
    void invalidTimezoneStillServesSummaryWithExplicitWindow() throws Exception {
        jdbcClient
                .sql("update users set timezone = 'Bukan/Zona' where id = :id")
                .param("id", user.getId())
                .update();

        mockMvc.perform(
                        get("/api/v1/summary")
                                .cookie(session)
                                .param("from", "2026-10-01")
                                .param("to", "2026-10-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-10-01"))
                .andExpect(jsonPath("$.kpi.totalExpense").value(42000));
    }

    @Test
    void tableGroupWithoutBudgetLeavesNullsButOtherCategoryKeepsBudget() throws Exception {
        String body =
                mockMvc.perform(
                                get("/api/v1/summary/table")
                                        .cookie(session)
                                        .param("from", "2026-10-01")
                                        .param("to", "2026-10-31"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        com.fasterxml.jackson.databind.JsonNode groups =
                objectMapper.readTree(body).get("groups");
        com.fasterxml.jackson.databind.JsonNode transport = null;
        com.fasterxml.jackson.databind.JsonNode makan = null;
        for (com.fasterxml.jackson.databind.JsonNode group : groups) {
            if ("Transportasi".equals(group.get("categoryName").asText())) {
                transport = group;
            }
            if ("Makanan".equals(group.get("categoryName").asText())) {
                makan = group;
            }
        }
        org.assertj.core.api.Assertions.assertThat(transport.hasNonNull("budgetAmount")).isFalse();
        org.assertj.core.api.Assertions.assertThat(makan.get("budgetAmount").asLong())
                .isEqualTo(20_000L * 31);
        org.assertj.core.api.Assertions.assertThat(makan.get("difference").asLong())
                .isEqualTo(30_000L - 20_000L * 31);
    }
}
