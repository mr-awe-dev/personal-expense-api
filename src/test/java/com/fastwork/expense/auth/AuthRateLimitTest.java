package com.fastwork.expense.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.fastwork.expense.user.UserRepository;
import com.fastwork.expense.user.UserService;

@SpringBootTest(
        properties = {
            "app.rate-limit.enabled=true",
            "app.rate-limit.ip-requests-per-minute=50",
            "app.rate-limit.email-requests-per-window=5",
            "app.rate-limit.delay-base-ms=0",
            "app.rate-limit.delay-max-ms=0"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthRateLimitTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "rate-limit-pass";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    void sixthLoginAttemptForSameEmailIsRateLimited() throws Exception {
        String email = "limiter@example.com";
        int attempts = mockMvc.perform(get("/api/v1/me")).andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(attempts).isEqualTo(401);

        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(
                            withCsrf(
                                    post("/api/v1/auth/login")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(loginBody(email, "wrong-" + i))))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(
                        withCsrf(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(loginBody(email, "wrong-6"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(
                        header().string(
                                        "Retry-After",
                                        org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")));
    }

    @Test
    void limitIsPerEmailSoFreshEmailStillCanLogin() throws Exception {
        userService.createUser("fresh-user@example.com", PASSWORD);

        mockMvc.perform(
                        withCsrf(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(loginBody("fresh-user@example.com", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("fresh-user@example.com"));
    }

    @Test
    void rateLimitOnlyAppliesToLoginEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk());
        MvcResult probe = mockMvc.perform(get("/api/v1/me")).andReturn();
        assertThat(probe.getResponse().getCookies()).extracting("name").contains(CSRF_COOKIE);
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder builder)
            throws Exception {
        Cookie csrf = csrfCookie();
        return builder.cookie(csrf).header(CSRF_HEADER, csrf.getValue());
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

    private String loginBody(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(
                objectMapper.readTree(
                        """
                {"email":"%s","password":"%s"}"""
                                .formatted(email, password)));
    }
}
