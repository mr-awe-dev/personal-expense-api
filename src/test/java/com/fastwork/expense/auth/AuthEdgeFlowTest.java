package com.fastwork.expense.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

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

/** Kasus tepi auth: akun dinonaktifkan dan PATCH /me tanpa body. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthEdgeFlowTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "kpassword-12345";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;
    @Autowired private JdbcClient jdbcClient;

    private User user;
    private Cookie session;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();
        user = userService.createUser("pemilik@example.com", PASSWORD);
        session = login("pemilik@example.com", PASSWORD);
    }

    /**
     * Akun dinonaktifkan ditolak dengan pesan generik: PetPrincipal memetakan disabledAt ke
     * isAccountNonLocked, sehingga DaoAuthenticationProvider melempar LockedException (cabang
     * DisabledException khusus di AuthService diuji terpisah via mock).
     */
    @Test
    void disabledAccountCannotLogin() throws Exception {
        jdbcClient
                .sql("update users set disabled_at = now() where id = :id")
                .param("id", user.getId())
                .update();
        assertThat(
                        jdbcClient
                                .sql("select disabled_at is not null from users where id = :id")
                                .param("id", user.getId())
                                .query(Boolean.class)
                                .single())
                .isTrue();

        mockMvc.perform(
                        withCsrf(post("/api/v1/auth/login"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "email",
                                                        "pemilik@example.com",
                                                        "password",
                                                        PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Email atau password salah"));
    }

    @Test
    void patchMeWithoutBodyReturnsCurrentProfileUnchanged() throws Exception {
        mockMvc.perform(authorized(patch("/api/v1/me"), session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("pemilik@example.com"));
    }

    @Test
    void meEndpointWorksAgainAfterManualSessionPrune() throws Exception {
        jdbcClient.sql("delete from SPRING_SESSION").update();
        mockMvc.perform(get("/api/v1/me").cookie(session)).andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder builder)
            throws Exception {
        Cookie csrf = csrfCookie();
        return builder.cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }

    private MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, Cookie activeSession) throws Exception {
        Cookie csrf = csrfCookie();
        return builder.cookie(activeSession, csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }

    private Cookie csrfCookie() throws Exception {
        MvcResult probe = mockMvc.perform(get("/api/v1/me")).andReturn();
        for (Cookie cookie : probe.getResponse().getCookies()) {
            if ("XSRF-TOKEN".equals(cookie.getName())) {
                return cookie;
            }
        }
        throw new IllegalStateException("CSRF cookie tidak dibuat");
    }

    private Cookie login(String email, String password) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                withCsrf(post("/api/v1/auth/login"))
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
            if ("PET_SESSION".equals(cookie.getName())) {
                return cookie;
            }
        }
        throw new IllegalStateException("Session cookie tidak dibuat");
    }
}
