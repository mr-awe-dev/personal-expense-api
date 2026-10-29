package com.fastwork.expense.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fastwork.expense.user.PetPrincipal;
import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRole;

class UserContextMdcFilterTest {

    private final UserContextMdcFilter filter = new UserContextMdcFilter();

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    private String runCapturingMdc() throws Exception {
        AtomicReference<String> during = new AtomicReference<>();
        FilterChain chain = (req, res) -> during.set(MDC.get(UserContextMdcFilter.MDC_USER_ID));
        filter.doFilter(
                new MockHttpServletRequest("GET", "/api/v1/me"),
                new MockHttpServletResponse(),
                chain);
        return during.get();
    }

    @Test
    void fillsUserIdOnlyFromAuthenticatedPrincipal() throws Exception {
        UUID id = UUID.randomUUID();
        PetPrincipal principal =
                new PetPrincipal(new User(id, "saya@example.com", "hash", UserRole.OWNER));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null));

        assertThat(runCapturingMdc()).isEqualTo(id.toString());
    }

    @Test
    void anonymousLeavesNoUserId() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "anonymousUser", null, java.util.List.of()));

        assertThat(runCapturingMdc()).isNull();
    }

    @Test
    void noAuthenticationLeavesNoUserId() throws Exception {
        assertThat(runCapturingMdc()).isNull();
    }

    @Test
    void mdcIsCleanedAfterChain() throws Exception {
        UUID id = UUID.randomUUID();
        PetPrincipal principal =
                new PetPrincipal(new User(id, "saya@example.com", "hash", UserRole.OWNER));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null));

        runCapturingMdc();

        assertThat(MDC.get(UserContextMdcFilter.MDC_USER_ID)).isNull();
    }
}
