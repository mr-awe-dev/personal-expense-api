package com.fastwork.expense.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
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
import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRepository;
import com.fastwork.expense.user.UserService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "kpassword-12345";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String SESSION_COOKIE = "PET_SESSION";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;

    private User user;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        user = userService.createUser("Pemilik@Example.com ", PASSWORD);
    }

    @Test
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk());
    }

    @Test
    void meRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginRejectsWrongPassword() throws Exception {
        mockMvc.perform(
                        withCsrf(post("/api/v1/auth/login"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json(
                                                """
                    {"email":"pemilik@example.com","password":"salah-sekali-123"}""")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Email atau password salah"));
    }

    @Test
    void loginRejectsBlankFields() throws Exception {
        mockMvc.perform(
                        withCsrf(post("/api/v1/auth/login"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json(
                                                """
                    {"email":"","password":""}""")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void loginSetsSessionAndProfileIsReadable() throws Exception {
        Cookie session = login("PEMILIK@example.com", PASSWORD, 200);

        assertThat(session).isNotNull();

        mockMvc.perform(get("/api/v1/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.timezone").value("Asia/Jakarta"))
                .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    void mutationsRequireCsrfToken() throws Exception {
        Cookie session = login("pemilik@example.com", PASSWORD, 200);

        mockMvc.perform(
                        patch("/api/v1/me")
                                .cookie(session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json(
                                                """
                    {"timezone":"Asia/Makassar"}""")))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateProfileChangesTimezoneAndDailyLimit() throws Exception {
        Cookie session = login("pemilik@example.com", PASSWORD, 200);

        mockMvc.perform(
                        authorized(patch("/api/v1/me"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json(
                                                """
                    {"timezone":"Asia/Makassar","dailyLimit":75000}""")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Asia/Makassar"))
                .andExpect(jsonPath("$.dailyLimit").value(75000));
    }

    @Test
    void updateProfileRejectsUnknownTimezone() throws Exception {
        Cookie session = login("pemilik@example.com", PASSWORD, 200);

        mockMvc.perform(
                        authorized(patch("/api/v1/me"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json(
                                                """
                    {"timezone":"Mars/Olympus"}""")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIMEZONE"));
    }

    @Test
    void changePasswordRequiresCurrentPasswordAndTakesEffect() throws Exception {
        Cookie session = login("pemilik@example.com", PASSWORD, 200);

        mockMvc.perform(
                        authorized(post("/api/v1/auth/change-password"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json(
                                                """
                    {"currentPassword":"password-salah-123","newPassword":"password-baru-12345"}""")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(
                        authorized(post("/api/v1/auth/change-password"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json(
                                                """
                    {"currentPassword":"%s","newPassword":"password-baru-12345"}"""
                                                        .formatted(PASSWORD))))
                .andExpect(status().isNoContent());

        login("pemilik@example.com", PASSWORD, 401);
        login("pemilik@example.com", "password-baru-12345", 200);
    }

    @Test
    void changePasswordRejectsShortPassword() throws Exception {
        Cookie session = login("pemilik@example.com", PASSWORD, 200);

        mockMvc.perform(
                        authorized(post("/api/v1/auth/change-password"), session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json(
                                                """
                    {"currentPassword":"%s","newPassword":"pendek"}"""
                                                        .formatted(PASSWORD))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void logoutInvalidatesSession() throws Exception {
        Cookie session = login("pemilik@example.com", PASSWORD, 200);

        mockMvc.perform(authorized(post("/api/v1/auth/logout"), session))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/me").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void csrfCookieIsIssuedToBrowser() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().exists(CSRF_COOKIE))
                .andExpect(cookie().httpOnly(CSRF_COOKIE, false));
    }

    @Test
    void duplicateEmailIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> userService.createUser("PEMILIK@example.com", PASSWORD))
                .isInstanceOf(com.fastwork.expense.common.ApiException.class)
                .hasMessageContaining("sudah terdaftar");
    }

    private Cookie login(String email, String password, int expectedStatus) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                withCsrf(post("/api/v1/auth/login"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json(
                                                        """
                        {"email":"%s","password":"%s"}"""
                                                                .formatted(email, password))))
                        .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        return sessionCookie(result);
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder builder)
            throws Exception {
        Cookie csrf = csrfCookie();
        return builder.cookie(csrf).header(CSRF_HEADER, csrf.getValue());
    }

    private MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, Cookie session) throws Exception {
        Cookie csrf = csrfCookie();
        return builder.cookie(session, csrf).header(CSRF_HEADER, csrf.getValue());
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

    private Cookie sessionCookie(MvcResult result) {
        for (Cookie cookie : result.getResponse().getCookies()) {
            if (SESSION_COOKIE.equals(cookie.getName())) {
                return cookie;
            }
        }
        return null;
    }

    private String json(String raw) throws Exception {
        return objectMapper.writeValueAsString(objectMapper.readTree(raw));
    }
}
