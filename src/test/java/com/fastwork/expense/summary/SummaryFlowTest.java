package com.fastwork.expense.summary;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
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
class SummaryFlowTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "ksummary-12345678";
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
    private LocalDate monthStart;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();
        user = userService.createUser("Pemilik@Example.com ", PASSWORD);
        session = login("pemilik@example.com", PASSWORD);
        today = LocalDate.now(ZoneId.of(user.getTimezone()));
        monthStart = today.withDayOfMonth(1);
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/summary")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/summary/table")).andExpect(status().isUnauthorized());
    }

    @Test
    void defaultsToCurrentMonthToDateWindow() throws Exception {
        Category makanan = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        seedExpense(today, "Hari ini", 10000L, makanan.getId());
        seedExpense(today.plusDays(1), "Besok", 90000L, makanan.getId());

        assertSummary()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(monthStart.toString()))
                .andExpect(jsonPath("$.to").value(today.toString()))
                .andExpect(jsonPath("$.kpi.totalExpense").value(10000))
                .andExpect(
                        jsonPath("$.trend.length()")
                                .value(
                                        java.time.temporal.ChronoUnit.DAYS.between(
                                                        monthStart, today)
                                                + 1));
    }

    @Test
    void kpiTotalsAndSavingsRate() throws Exception {
        Category gaji = categoryService.create(user.getId(), TxType.INCOME, "Gaji");
        Category makanan = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        seedIncome(today, "Gaji bulan ini", 250000L, gaji.getId());
        seedExpense(today, "Bakso", 30000L, makanan.getId());
        seedExpense(today, "Teh", 20000L, makanan.getId());

        assertSummary()
                .andExpect(jsonPath("$.kpi.totalIncome").value(250000))
                .andExpect(jsonPath("$.kpi.totalExpense").value(50000))
                .andExpect(jsonPath("$.kpi.net").value(200000))
                .andExpect(jsonPath("$.kpi.savingsRate").value(80));
    }

    @Test
    void negativeNetDropsSavingsRate() throws Exception {
        Category makanan = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        seedExpense(today, "Bakso", 50000L, makanan.getId());

        assertSummary()
                .andExpect(jsonPath("$.kpi.totalIncome").value(0))
                .andExpect(jsonPath("$.kpi.net").value(-50000))
                .andExpect(jsonPath("$.kpi.savingsRate").doesNotExist());
    }

    @Test
    void sharesUseLargestRemainderSummingTo100() throws Exception {
        categoryService.create(user.getId(), TxType.EXPENSE, "A");
        categoryService.create(user.getId(), TxType.EXPENSE, "B");
        categoryService.create(user.getId(), TxType.EXPENSE, "C");
        List<Category> categories = categoryService.list(user.getId(), TxType.EXPENSE, true);
        seedExpense(today, "satu", 100L, categories.get(0).getId());
        seedExpense(today, "dua", 100L, categories.get(1).getId());
        seedExpense(today, "tiga", 100L, categories.get(2).getId());

        assertSummary()
                .andExpect(jsonPath("$.expenseByCategory.length()").value(3))
                .andExpect(jsonPath("$.expenseByCategory[0].share").value(34))
                .andExpect(jsonPath("$.expenseByCategory[1].share").value(33))
                .andExpect(jsonPath("$.expenseByCategory[2].share").value(33));
    }

    @Test
    void singleCategoryShareIs100() throws Exception {
        Category makanan = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        seedExpense(today, "Bakso", 30000L, makanan.getId());

        assertSummary()
                .andExpect(jsonPath("$.expenseByCategory.length()").value(1))
                .andExpect(jsonPath("$.expenseByCategory[0].share").value(100))
                .andExpect(jsonPath("$.expenseByCategory[0].categoryName").value("Makanan"));
    }

    @Test
    void uncategorizedExpenseAppearsWithoutName() throws Exception {
        seedExpense(today, "Tanpa kategori", 12000L, null);

        assertSummary()
                .andExpect(jsonPath("$.expenseByCategory.length()").value(1))
                .andExpect(jsonPath("$.expenseByCategory[0].categoryId").doesNotExist())
                .andExpect(jsonPath("$.expenseByCategory[0].categoryName").doesNotExist())
                .andExpect(jsonPath("$.expenseByCategory[0].amount").value(12000));
    }

    @Test
    void incomeGroupedBySource() throws Exception {
        Category gaji = categoryService.create(user.getId(), TxType.INCOME, "Gaji");
        Category freelance = categoryService.create(user.getId(), TxType.INCOME, "Freelance");
        seedIncome(today, "Gaji bulan ini", 300000L, gaji.getId());
        seedIncome(today, "Kerja sebelah", 100000L, freelance.getId());

        assertSummary()
                .andExpect(jsonPath("$.incomeBySource.length()").value(2))
                .andExpect(jsonPath("$.incomeBySource[0].amount").value(300000))
                .andExpect(jsonPath("$.incomeBySource[0].share").value(75))
                .andExpect(jsonPath("$.incomeBySource[1].amount").value(100000))
                .andExpect(jsonPath("$.incomeBySource[1].share").value(25));
    }

    @Test
    void budgetVsActualScalesDailyAcrossTheRange() throws Exception {
        categoryService.create(user.getId(), TxType.EXPENSE, "Bulanan");
        categoryService.create(user.getId(), TxType.EXPENSE, "Harian");
        List<Category> categories = categoryService.list(user.getId(), TxType.EXPENSE, true);
        UUID monthlyId = categories.get(0).getId();
        UUID dailyId = categories.get(1).getId();
        budgetService.upsert(
                user.getId(),
                monthlyId,
                new UpsertBudgetRequest(BudgetPeriod.MONTHLY, 200000L, null));
        budgetService.upsert(
                user.getId(), dailyId, new UpsertBudgetRequest(BudgetPeriod.DAILY, 10000L, null));
        seedExpense(today, "Habis bulanan", 150000L, monthlyId);
        seedExpense(today, "Sekali", 5000L, dailyId);
        LocalDate from = today;
        LocalDate to = today.plusDays(3);

        assertSummary(from, to)
                .andExpect(jsonPath("$.budgetVsActual.length()").value(2))
                .andExpect(jsonPath("$.budgetVsActual[0].categoryArchived").value(false))
                .andExpect(jsonPath("$.budgetVsActual[0].period").value("MONTHLY"))
                .andExpect(jsonPath("$.budgetVsActual[0].budgetAmount").value(200000))
                .andExpect(jsonPath("$.budgetVsActual[0].spent").value(150000))
                .andExpect(jsonPath("$.budgetVsActual[0].remaining").value(50000))
                .andExpect(jsonPath("$.budgetVsActual[1].period").value("DAILY"))
                .andExpect(jsonPath("$.budgetVsActual[1].spent").value(5000))
                .andExpect(jsonPath("$.budgetVsActual[1].budgetAmount").value(40000))
                .andExpect(jsonPath("$.budgetVsActual[1].remaining").value(35000));
    }

    @Test
    void trendFillsMissingDaysWithZeros() throws Exception {
        Category makanan = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        LocalDate from = today;
        seedExpense(today, "Pagi", 20000L, makanan.getId());
        seedExpense(today.plusDays(2), "Lusa", 5000L, makanan.getId());

        assertSummary(from, today.plusDays(3))
                .andExpect(jsonPath("$.trend.length()").value(4))
                .andExpect(jsonPath("$.trend[0].expense").value(20000))
                .andExpect(jsonPath("$.trend[1].expense").value(0))
                .andExpect(jsonPath("$.trend[2].expense").value(5000))
                .andExpect(jsonPath("$.trend[3].expense").value(0))
                .andExpect(jsonPath("$.trend[2].date").value(today.plusDays(2).toString()));
    }

    @Test
    void invalidRangeGivesBadRequest() throws Exception {
        mockMvc.perform(
                        get("/api/v1/summary")
                                .cookie(session)
                                .param("from", today.plusDays(2).toString())
                                .param("to", today.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RANGE"));

        mockMvc.perform(
                        get("/api/v1/summary/table")
                                .cookie(session)
                                .param("from", today.plusDays(2).toString())
                                .param("to", today.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void closedTransactionsDoNotCount() throws Exception {
        UUID id = seedExpense(today, "Ditutup", 40000L, null);

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

        assertSummary()
                .andExpect(jsonPath("$.kpi.totalExpense").value(0))
                .andExpect(jsonPath("$.expenseByCategory.length()").value(0));
        mockMvc.perform(get("/api/v1/summary/table").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(0))
                .andExpect(jsonPath("$.totals.net").value(0));
    }

    @Test
    void foreignUserTransactionsAreInvisible() throws Exception {
        User other = userService.createUser("OrangLain@Example.com", PASSWORD);
        Category otherCat =
                categoryService.create(other.getId(), TxType.EXPENSE, "KategoriOrangLain");
        transactionService.upsert(
                other.getId(),
                UUID.randomUUID(),
                new UpsertTransactionRequest(
                        today, TxType.EXPENSE, otherCat.getId(), "Bukan punyaku", 500000L));
        seedExpense(today, "Punyaku", 10000L, null);

        assertSummary().andExpect(jsonPath("$.kpi.totalExpense").value(10000));
    }

    @Test
    void tableGroupsSubtotalsAndBudgetDifference() throws Exception {
        Category makanan = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        Category gaji = categoryService.create(user.getId(), TxType.INCOME, "Gaji");
        budgetService.upsert(
                user.getId(),
                makanan.getId(),
                new UpsertBudgetRequest(BudgetPeriod.MONTHLY, 100000L, null));
        seedExpense(today, "Bakso", 30000L, makanan.getId());
        seedExpense(today, "Teh", 20000L, makanan.getId());
        seedExpense(today.plusDays(1), "Sate", 5000L, null);
        seedIncome(today, "Gaji bulan ini", 400000L, gaji.getId());

        mockMvc.perform(
                        get("/api/v1/summary/table")
                                .cookie(session)
                                .param("from", monthStart.toString())
                                .param("to", today.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(3))
                // Urutan: expense tanpa kategori (nama null) dulu, lalu Makanan, lalu income.
                .andExpect(jsonPath("$.groups[0].type").value("EXPENSE"))
                .andExpect(jsonPath("$.groups[0].categoryName").doesNotExist())
                .andExpect(jsonPath("$.groups[0].subtotal").value(5000))
                .andExpect(jsonPath("$.groups[0].budgetAmount").doesNotExist())
                .andExpect(jsonPath("$.groups[1].categoryId").value(makanan.getId().toString()))
                .andExpect(jsonPath("$.groups[1].subtotal").value(50000))
                .andExpect(jsonPath("$.groups[1].budgetAmount").value(100000))
                .andExpect(jsonPath("$.groups[1].difference").value(-50000))
                .andExpect(jsonPath("$.groups[1].rows.length()").value(2))
                .andExpect(jsonPath("$.groups[2].type").value("INCOME"))
                .andExpect(jsonPath("$.groups[2].subtotal").value(400000))
                .andExpect(jsonPath("$.totals.income").value(400000))
                .andExpect(jsonPath("$.totals.expense").value(55000))
                .andExpect(jsonPath("$.totals.net").value(345000))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void tableTruncatesRowsButKeepsSubtotalsExact() throws Exception {
        Category makanan = categoryService.create(user.getId(), TxType.EXPENSE, "Makanan");
        Category jajan = categoryService.create(user.getId(), TxType.EXPENSE, "Jajan");
        seedExpense(today, "satu", 1000L, makanan.getId());
        seedExpense(today, "dua", 2000L, makanan.getId());
        seedExpense(today, "tiga", 3000L, jajan.getId());
        seedExpense(today, "empat", 4000L, jajan.getId());

        mockMvc.perform(get("/api/v1/summary/table").cookie(session).param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(2))
                .andExpect(jsonPath("$.groups[0].categoryName").value("Jajan"))
                .andExpect(jsonPath("$.groups[0].subtotal").value(7000))
                .andExpect(jsonPath("$.groups[1].categoryName").value("Makanan"))
                .andExpect(jsonPath("$.groups[1].subtotal").value(3000))
                .andExpect(jsonPath("$.truncated").value(true));
    }

    private ResultActions assertSummary() throws Exception {
        return mockMvc.perform(get("/api/v1/summary").cookie(session));
    }

    private ResultActions assertSummary(LocalDate from, LocalDate to) throws Exception {
        return mockMvc.perform(
                get("/api/v1/summary")
                        .cookie(session)
                        .param("from", from.toString())
                        .param("to", to.toString()));
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
