package com.fastwork.expense.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MonolithModulesTest {

    @Test
    void derivesFirstSegmentUnderRootPackage() {
        assertThat(
                        MonolithModules.fromLogger(
                                "com.fastwork.expense.transaction.TransactionService"))
                .isEqualTo("transaction");
        assertThat(MonolithModules.fromLogger("com.fastwork.expense.auth.AuthService"))
                .isEqualTo("auth");
        assertThat(MonolithModules.fromLogger("com.fastwork.expense.observability.LoggingAspect"))
                .isEqualTo("observability");
    }

    @Test
    void rootLevelClassIsApp() {
        assertThat(MonolithModules.fromLogger("com.fastwork.expense.ExpenseApplication"))
                .isEqualTo("app");
    }

    @Test
    void anythingOutsideOurPackageIsPlatform() {
        assertThat(MonolithModules.fromLogger("org.hibernate.SQL")).isEqualTo("platform");
        assertThat(MonolithModules.fromLogger("org.springframework.security.web.FilterChain"))
                .isEqualTo("platform");
        assertThat(MonolithModules.fromLogger(null)).isEqualTo("platform");
        assertThat(MonolithModules.fromLogger("com.fastwork.expense.")).isEqualTo("platform");
    }
}
