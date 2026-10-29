package com.fastwork.expense.today;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.ResultActions;

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
class TodayFlowTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "ktoday-12345678";
    private static final String SESSION_COOKIE = "PET_SESSION";

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

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();
        user = userService.createUser("Pemilik@Example.com ", PASSWORD);
        session = login("pemilik@example.com", PASSWORD);
        today = LocalDate.now(ZoneId.of(user.getTimezone()));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/today")).andExpect(status().isUnauthorized());
    }

    @Test
    void withoutDailyLimitShowsSpentTotalOnly() throws Exception {
        Category makanan = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        budgetService.upsert(
                user.getId(),
                makanan.getId(),
                new UpsertBudgetRequest(BudgetPeriod.DAILY, 50000L, null));
        seedExpense(today, "Teh hangat", 25000L, makanan.getId());

        mockMvc.perform(get("/api/v1/today").cookie(session).param("date", today.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(today.toString()))
                .andExpect(jsonPath("$.spentToday").value(25000))
                .andExpect(jsonPath("$.dailyLimit").doesNotExist())
                .andExpect(jsonPath("$.remaining").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.categories.length()").value(1))
                .andExpect(jsonPath("$.categories[0].categoryName").value("Makanan"))
                .andExpect(jsonPath("$.categories[0].limitAmount").value(50000))
                .andExpect(jsonPath("$.categories[0].spent").value(25000))
                .andExpect(jsonPath("$.categories[0].remaining").value(25000))
                .andExpect(jsonPath("$.categories[0].categoryArchived").value(false));
    }

    @Test
    void remainingAndStatusFollowUsageThresholds() throws Exception {
        user.setDailyLimit(100000L);
        user = userRepository.save(user);
        Category makanan = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        budgetService.upsert(
                user.getId(),
                makanan.getId(),
                new UpsertBudgetRequest(BudgetPeriod.DAILY, 50000L, null));

        seedExpense(today, "Pagi", 20000L, makanan.getId());
        assertToday()
                .andExpect(jsonPath("$.status").value("AMAN"))
                .andExpect(jsonPath("$.remaining").value(80000));

        seedExpense(today, "Siang", 70000L, makanan.getId());
        assertToday()
                .andExpect(jsonPath("$.status").value("WASPADA"))
                .andExpect(jsonPath("$.remaining").value(10000));

        seedExpense(today, "Malam", 30000L, makanan.getId());
        assertToday()
                .andExpect(jsonPath("$.status").value("MELEBIHI"))
                .andExpect(jsonPath("$.remaining").value(-20000))
                .andExpect(jsonPath("$.dailyLimit").value(100000))
                .andExpect(jsonPath("$.spentToday").value(120000));
    }

    @Test
    void monthlyBudgetIsNotListed() throws Exception {
        Category makanan = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        budgetService.upsert(
                user.getId(),
                makanan.getId(),
                new UpsertBudgetRequest(BudgetPeriod.MONTHLY, 900000L, null));
        seedExpense(today, "Teh", 8000L, makanan.getId());

        assertToday()
                .andExpect(jsonPath("$.spentToday").value(8000))
                .andExpect(jsonPath("$.categories.length()").value(0));
    }

    @Test
    void incomeDoesNotConsumeTheLimit() throws Exception {
        user.setDailyLimit(50000L);
        user = userRepository.save(user);
        Category makanan = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        Category gaji = categoryService.create(user.getId(), TxType.INCOME, "Gaji");
        seedExpense(today, "Bakso", 30000L, makanan.getId());
        seedIncome(today, "Freelance", 250000L, gaji.getId());

        assertToday()
                .andExpect(jsonPath("$.spentToday").value(30000))
                .andExpect(jsonPath("$.remaining").value(20000));
    }

    @Test
    void closedTransactionsDoNotCount() throws Exception {
        user.setDailyLimit(50000L);
        user = userRepository.save(user);
        UUID id = seedExpense(today, "Siatap", 40000L, null);

        assertToday().andExpect(jsonPath("$.spentToday").value(40000));

        UUID closingId = UUID.randomUUID();
        jdbcClient
                .sql(
                        """
                        insert into closings (id, user_id, period_start, period_end,
                                              total_income, total_expense, net, tx_count, file_name)
                        values (:closingId, :userId, :start, :end, 0, 0, 0, 0, 'test.xlsx')
                        """)
                .param("closingId", closingId)
                .param("userId", user.getId())
                .param("start", today)
                .param("end", today)
                .update();
        jdbcClient
                .sql("update transactions set closing_id = :closingId where id = :id")
                .param("closingId", closingId)
                .param("id", id)
                .update();

        assertToday().andExpect(jsonPath("$.spentToday").value(0));
    }

    @Test
    void uncategorizedExpenseOnlyTotalsAndNotInBreakdown() throws Exception {
        user.setDailyLimit(200000L);
        user = userRepository.save(user);
        Category makanan = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        budgetService.upsert(
                user.getId(),
                makanan.getId(),
                new UpsertBudgetRequest(BudgetPeriod.DAILY, 60000L, null));
        seedExpense(today, "Tanpa kategori", 15000L, null);
        seedExpense(today, "Susu", 12000L, makanan.getId());

        assertToday()
                .andExpect(jsonPath("$.spentToday").value(27000))
                .andExpect(jsonPath("$.categories.length()").value(1))
                .andExpect(jsonPath("$.categories[0].spent").value(12000));
    }

    @Test
    void dateDefaultsToTheUserTimezone() throws Exception {
        user.setTimezone("Pacific/Auckland");
        user = userRepository.save(user);
        String az = LocalDate.now(ZoneId.of("Pacific/Auckland")).toString();
        mockMvc.perform(get("/api/v1/today").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(az));
    }

    @Test
    void invalidDateGivesBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/today").cookie(session).param("date", "31/2/2025"))
                .andExpect(status().isBadRequest());
    }

    private ResultActions assertToday() throws Exception {
        return mockMvc.perform(
                get("/api/v1/today").cookie(session).param("date", today.toString()));
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

    private Cookie login(String email, String password) throws Exception {
        Cookie csrf = csrfCookie();
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .cookie(csrf)
                                        .header("X-XSRF-TOKEN", csrf.getValue())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "email",
                                                                email,
                                                                "password",
                                                                password))))
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
            if ("XSRF-TOKEN".equals(cookie.getName())) {
                return cookie;
            }
        }
        throw new IllegalStateException("Cookie XSRF-TOKEN tidak dibuat");
    }
}
