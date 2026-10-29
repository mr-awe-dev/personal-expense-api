package com.fastwork.expense.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fastwork.expense.PostgresIntegrationTest;

/**
 * Konteks dengan HSTS aktif ({@code app.security.hsts-enabled=true}) — menutup cabang filterChain
 * yang tidak pernah berjalan di profile test (HSTS mati). Header hanya ditulis pada request secure,
 * jadi cukup verifikasi boot + health 200.
 */
@SpringBootTest(properties = "app.security.hsts-enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HstsEnabledFlowTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void contextBootsWithHstsEnabledAndServesHealth() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'self'")));
    }
}
