package com.fastwork.expense.today;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneOffset;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.fastwork.expense.WebFlowTestBase;
import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRepository;

/** Zona waktu profil tidak valid (mis. ulah migrasi manual) → fallback UTC. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TodayEdgeFlowTest extends WebFlowTestBase {

    @Autowired private UserRepository userRepository;
    @Autowired private JdbcClient jdbcClient;

    private User user;
    private Cookie session;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();
        user = userService.createUser("pemilik@example.com", PASSWORD);
        session = login("pemilik@example.com");
    }

    @Test
    void invalidTimezoneFallsBackToUtcToday() throws Exception {
        jdbcClient
                .sql("update users set timezone = 'Bukan/Zona' where id = :id")
                .param("id", user.getId())
                .update();

        mockMvc.perform(get("/api/v1/today").cookie(session))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.date")
                                .value(LocalDate.now(ZoneOffset.UTC).toString()));
    }
}
