package com.fastwork.expense.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fastwork.expense.PostgresIntegrationTest;
import com.fastwork.expense.user.UserRepository;

@SpringBootTest(
        properties = {
            "app.rate-limit.enabled=true",
            "app.rate-limit.ip-requests-per-minute=50",
            "app.rate-limit.email-requests-per-window=5",
            "app.rate-limit.delay-base-ms=2000",
            "app.rate-limit.delay-max-ms=2000"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthRateLimitDelayTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "delay-pass-123";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    void immediateRetryAfterFailureIsBlockedUntilDelayWindowPasses() throws Exception {
        String email = "delayed@example.com";

        attemptLogin(email, "first-wrong").andExpect(status().isUnauthorized());

        attemptLogin(email, "second-wrong")
                .andExpect(status().isTooManyRequests())
                .andExpect(
                        header().string(
                                        "Retry-After",
                                        org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")));

        try {
            Thread.sleep(2_100);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }

        attemptLogin(email, "post-wait").andExpect(status().isUnauthorized());
    }

    private ResultActions attemptLogin(String email, String password) throws Exception {
        return mockMvc.perform(
                withCsrf(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginBody(email, password))));
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
