package com.fastwork.expense.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

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
import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRepository;
import com.fastwork.expense.user.UserService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetFlowTest extends PostgresIntegrationTest {

    private static final String EMAIL = "pemilik@example.com";
    private static final String PASSWORD = "kpassword-12345";
    private static final String NEW_PASSWORD = "password-baru-12345";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String SESSION_COOKIE = "PET_SESSION";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;
    @Autowired private PasswordResetService passwordResetService;
    @Autowired private PasswordResetRepository passwordResetRepository;
    @Autowired private JdbcClient jdbcClient;

    private User user;

    @BeforeEach
    void setUp() {
        passwordResetRepository.deleteAll();
        userRepository.deleteAll();
        user = userService.createUser(EMAIL, PASSWORD);
    }

    @Test
    void issuedCodeResetsPasswordAndBlocksOldPassword() throws Exception {
        String code = passwordResetService.issue(user.getEmail());

        resetRequest(emailCodePasswordJson(EMAIL, code, NEW_PASSWORD), 204);

        login(EMAIL, PASSWORD, 401);
        login(EMAIL, NEW_PASSWORD, 200);
        resetRequest(emailCodePasswordJson(EMAIL, code, "password-lain-1234"), 400);
    }

    @Test
    void lowercaseOrSpacedCodeIsAccepted() throws Exception {
        String code = passwordResetService.issue(EMAIL);

        resetRequest(
                emailCodePasswordJson(EMAIL, "  " + code.toLowerCase() + " ", NEW_PASSWORD), 204);

        login(EMAIL, NEW_PASSWORD, 200);
    }

    @Test
    void wrongCodeIsRejectedWithGenericMessage() throws Exception {
        passwordResetService.issue(EMAIL);

        resetRequest(
                emailCodePasswordJson(EMAIL, "DEADBEEFDEADBEEFDEADBEEF", NEW_PASSWORD),
                400,
                "INVALID_RESET_CODE");
    }

    @Test
    void unknownEmailUsesSameGenericErrorAsWrongCode() throws Exception {
        String body =
                emailCodePasswordJson(
                        "tidak-ada@example.com", "DEADBEEFDEADBEEFDEADBEEF", NEW_PASSWORD);

        resetRequest(body, 400, "INVALID_RESET_CODE");
    }

    @Test
    void expiredCodeIsRejected() throws Exception {
        String code = passwordResetService.issue(EMAIL);
        jdbcClient
                .sql("update password_resets set expires_at = :past")
                .param("past", java.sql.Timestamp.from(Instant.now().minusSeconds(60)))
                .update();

        resetRequest(emailCodePasswordJson(EMAIL, code, NEW_PASSWORD), 400, "INVALID_RESET_CODE");
    }

    @Test
    void weakNewPasswordIsRejectedAndCodeSurvives() throws Exception {
        String code = passwordResetService.issue(EMAIL);

        resetRequest(emailCodePasswordJson(EMAIL, code, "pendek"), 400, "VALIDATION_ERROR");

        resetRequest(emailCodePasswordJson(EMAIL, code, NEW_PASSWORD), 204);
    }

    @Test
    void secondIssueInvalidatesFirstCode() throws Exception {
        String first = passwordResetService.issue(EMAIL);
        String second = passwordResetService.issue(EMAIL);

        resetRequest(emailCodePasswordJson(EMAIL, first, NEW_PASSWORD), 400, "INVALID_RESET_CODE");

        resetRequest(emailCodePasswordJson(EMAIL, second, NEW_PASSWORD), 204);
    }

    @Test
    void resetInvalidatesActiveSessions() throws Exception {
        Cookie session = login(EMAIL, PASSWORD, 200);
        assertThat(session).isNotNull();
        mockMvc.perform(get("/api/v1/me").cookie(session)).andExpect(status().isOk());

        String code = passwordResetService.issue(EMAIL);
        resetRequest(emailCodePasswordJson(EMAIL, code, NEW_PASSWORD), 204);

        mockMvc.perform(get("/api/v1/me").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void resetEndpointIsPublicButNeedsCsrf() throws Exception {
        String code = passwordResetService.issue(EMAIL);

        mockMvc.perform(
                        post("/api/v1/auth/reset-password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(emailCodePasswordJson(EMAIL, code, NEW_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    private void resetRequest(String json, int expectedStatus) throws Exception {
        resetRequest(json, expectedStatus, null);
    }

    private void resetRequest(String json, int expectedStatus, String expectedCode)
            throws Exception {
        MvcResult result =
                mockMvc.perform(
                                withCsrf(post("/api/v1/auth/reset-password"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json))
                        .andExpect(status().is(expectedStatus))
                        .andReturn();
        if (expectedCode != null) {
            assertThat(readCode(result)).isEqualTo(expectedCode);
        }
    }

    private String readCode(MvcResult result) throws Exception {
        return objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("code")
                .asText();
    }

    private String emailCodePasswordJson(String email, String code, String password)
            throws Exception {
        return objectMapper.writeValueAsString(new ResetPasswordRequest(email, code, password));
    }

    private Cookie login(String email, String password, int expectedStatus) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                withCsrf(post("/api/v1/auth/login"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        new LoginRequest(email, password))))
                        .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        for (Cookie cookie : result.getResponse().getCookies()) {
            if (SESSION_COOKIE.equals(cookie.getName())) {
                return cookie;
            }
        }
        return null;
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
}
