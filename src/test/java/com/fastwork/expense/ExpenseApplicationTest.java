package com.fastwork.expense;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ExpenseApplicationTest extends PostgresIntegrationTest {

    @Test
    void contextLoads() {
        // Memastikan application context berhasil dimuat.
    }
}
