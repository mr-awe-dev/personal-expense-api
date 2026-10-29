package com.fastwork.expense.budget;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fastwork.expense.PostgresIntegrationTest;
import com.fastwork.expense.category.Category;
import com.fastwork.expense.category.CategoryService;
import com.fastwork.expense.common.TxType;
import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRepository;
import com.fastwork.expense.user.UserService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BudgetFlowTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "kbudget-1234567";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String SESSION_COOKIE = "PET_SESSION";
    private static final UUID UNKNOWN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;
    @Autowired private CategoryService categoryService;

    private User user;
    private Cookie session;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();
        user = userService.createUser("Pemilik@Example.com ", PASSWORD);
        session = login("pemilik@example.com", PASSWORD);
    }

    @Test
    void listRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/budgets")).andExpect(status().isUnauthorized());
    }

    @Test
    void upsertCreatesThenReplacesWithoutDuplicates() throws Exception {
        Category transport = createExpense("Transport");

        mockMvc.perform(putBudget(transport.getId(), daily(50000, "budget commuter")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(transport.getId().toString()))
                .andExpect(jsonPath("$.categoryName").value("Transport"))
                .andExpect(jsonPath("$.period").value("DAILY"))
                .andExpect(jsonPath("$.amount").value(50000))
                .andExpect(jsonPath("$.description").value("budget commuter"))
                .andExpect(jsonPath("$.categoryArchived").value(false));

        mockMvc.perform(
                        putBudget(
                                transport.getId(),
                                Map.of(
                                        "period",
                                        "MONTHLY",
                                        "amount",
                                        2000000,
                                        "description",
                                        "budget bulanan")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("MONTHLY"))
                .andExpect(jsonPath("$.amount").value(2000000))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        mockMvc.perform(get("/api/v1/budgets").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void upsertRejectsIncomeCategory() throws Exception {
        Category gaji = categoryService.create(user.getId(), TxType.INCOME, "Gaji");

        mockMvc.perform(putBudget(gaji.getId(), Map.of("period", "DAILY", "amount", 10000)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE_ENTITY"))
                .andExpect(jsonPath("$.message").value("Budget hanya untuk kategori pengeluaran"));
    }

    @Test
    void upsertRejectsUnknownCategory() throws Exception {
        mockMvc.perform(putBudget(UNKNOWN_ID, Map.of("period", "DAILY", "amount", 10000)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void upsertValidatesPayload() throws Exception {
        Category transport = createExpense("Transport");

        for (Map<String, Object> body :
                java.util.List.<Map<String, Object>>of(
                        Map.of("period", "DAILY", "amount", -1),
                        Map.of("period", "DAILY", "amount", 1000000000000L),
                        Map.of("period", "WEEKLY", "amount", 10000),
                        Map.of(
                                "period",
                                "DAILY",
                                "amount",
                                10000,
                                "description",
                                "a".repeat(201)))) {
            mockMvc.perform(putBudget(transport.getId(), body)).andExpect(status().isBadRequest());
        }

        mockMvc.perform(
                        authorized(put("/api/v1/budgets/" + transport.getId()), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":10000}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listJoinsCategoryInfoAndSortsByName() throws Exception {
        Category transport = createExpense("Transport");
        Category makanan = createExpense("Makanan");
        mockMvc.perform(putBudget(transport.getId(), Map.of("period", "DAILY", "amount", 30000)))
                .andExpect(status().isOk());
        mockMvc.perform(putBudget(makanan.getId(), Map.of("period", "MONTHLY", "amount", 1500000)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/budgets").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].categoryName").value("Makanan"))
                .andExpect(jsonPath("$[0].period").value("MONTHLY"))
                .andExpect(jsonPath("$[1].categoryName").value("Transport"))
                .andExpect(jsonPath("$[1].period").value("DAILY"));
    }

    @Test
    void deleteRemovesAndThenRejectsRepeatedDelete() throws Exception {
        Category transport = createExpense("Transport");
        mockMvc.perform(putBudget(transport.getId(), Map.of("period", "DAILY", "amount", 30000)))
                .andExpect(status().isOk());

        mockMvc.perform(authorized(delete("/api/v1/budgets/" + transport.getId()), session))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/budgets").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(authorized(delete("/api/v1/budgets/" + transport.getId()), session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void archivedCategoryBudgetStillListed() throws Exception {
        Category transport = createExpense("Transport");
        mockMvc.perform(putBudget(transport.getId(), Map.of("period", "DAILY", "amount", 30000)))
                .andExpect(status().isOk());
        categoryService.update(user.getId(), transport.getId(), null, true);

        mockMvc.perform(get("/api/v1/budgets").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].categoryArchived").value(true));
    }

    @Test
    void otherUserSeesNoBudgetAndCannotUpsertForeignCategory() throws Exception {
        Category transport = createExpense("Transport");
        mockMvc.perform(putBudget(transport.getId(), Map.of("period", "DAILY", "amount", 30000)))
                .andExpect(status().isOk());

        userService.createUser("OrangLain@example.com", PASSWORD);
        Cookie otherSession = login("oranglain@example.com", PASSWORD);

        mockMvc.perform(get("/api/v1/budgets").cookie(otherSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        Cookie otherCsrf = csrfCookie();
        mockMvc.perform(
                        put("/api/v1/budgets/" + transport.getId())
                                .cookie(otherSession, otherCsrf)
                                .header(CSRF_HEADER, otherCsrf.getValue())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("period", "DAILY", "amount", 99000))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private Category createExpense(String name) {
        return categoryService.create(user.getId(), TxType.EXPENSE, name);
    }

    private static Map<String, Object> daily(int amount, String description) {
        return Map.of("period", "DAILY", "amount", amount, "description", description);
    }

    private MockHttpServletRequestBuilder putBudget(UUID categoryId, Map<String, Object> body)
            throws Exception {
        return authorized(put("/api/v1/budgets/" + categoryId), session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body));
    }

    private MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, Cookie session) throws Exception {
        Cookie csrf = csrfCookie();
        return builder.cookie(session, csrf).header(CSRF_HEADER, csrf.getValue());
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
