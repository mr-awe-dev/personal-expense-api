package com.fastwork.expense.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fastwork.expense.PostgresIntegrationTest;
import com.fastwork.expense.common.TxType;
import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRepository;
import com.fastwork.expense.user.UserService;

/**
 * Regresi keamanan query dinamis (§10): semua nilai ter-bind, sort berupa enum whitelist, LIKE
 * di-escape, cursor tervalidasi — input jahat harus ditolak/di-netralisasi, bukan dieksekusi.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransactionQuerySafetyTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "kpassword-12345";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;
    @Autowired private TransactionService transactionService;

    private User user;
    private Cookie session;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();
        user = userService.createUser("pemilik@example.com", PASSWORD);
        session = login();
        upsert("Bayar 50% DP", 50000L);
        upsert("Kopi pagi", 15000L);
    }

    @Test
    void maliciousSortValueIsRejectedNotInterpreted() throws Exception {
        mockMvc.perform(
                        get("/api/v1/transactions")
                                .cookie(session)
                                .param("sort", "DATE_DESC; DROP TABLE users"))
                .andExpect(status().isBadRequest());
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void sqlPayloadInFreeTextSearchIsTreatedAsLiteral() throws Exception {
        mockMvc.perform(get("/api/v1/transactions").cookie(session).param("q", "' OR '1'='1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(0));
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void likeWildcardsInSearchAreEscaped() throws Exception {
        mockMvc.perform(get("/api/v1/transactions").cookie(session).param("q", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].description").value("Bayar 50% DP"));

        mockMvc.perform(get("/api/v1/transactions").cookie(session).param("q", "50%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].description").value("Bayar 50% DP"));
    }

    @Test
    void malformedCursorIsRejected() throws Exception {
        mockMvc.perform(
                        get("/api/v1/transactions")
                                .cookie(session)
                                .param("cursor", "bukan-cursor;select 1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }

    private void upsert(String description, long amount) {
        transactionService.upsert(
                user.getId(),
                UUID.randomUUID(),
                new UpsertTransactionRequest(
                        LocalDate.of(2026, 10, 15), TxType.EXPENSE, null, description, amount));
    }

    private Cookie login() throws Exception {
        MvcResult result =
                mockMvc.perform(
                                withCsrf(post("/api/v1/auth/login"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        java.util.Map.of(
                                                                "email",
                                                                "pemilik@example.com",
                                                                "password",
                                                                PASSWORD))))
                        .andExpect(status().isOk())
                        .andReturn();
        for (Cookie cookie : result.getResponse().getCookies()) {
            if ("PET_SESSION".equals(cookie.getName())) {
                return cookie;
            }
        }
        throw new IllegalStateException("Session cookie tidak dibuat");
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder builder)
            throws Exception {
        MvcResult probe = mockMvc.perform(get("/api/v1/me")).andReturn();
        for (Cookie cookie : probe.getResponse().getCookies()) {
            if ("XSRF-TOKEN".equals(cookie.getName())) {
                return builder.cookie(cookie).header("X-XSRF-TOKEN", cookie.getValue());
            }
        }
        throw new IllegalStateException("CSRF cookie tidak dibuat");
    }
}
