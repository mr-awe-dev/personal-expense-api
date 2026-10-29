package com.fastwork.expense.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.core.JsonGenerator;

import ch.qos.logback.classic.spi.ILoggingEvent;

class ObservabilityUnitTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void jsonProviderWritesMonolithModule() throws Exception {
        MonolithModuleJsonProvider provider = new MonolithModuleJsonProvider();
        JsonGenerator generator = mock(JsonGenerator.class);
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getLoggerName()).thenReturn("com.fastwork.expense.transaction.TransactionService");

        provider.writeTo(generator, event);

        verify(generator).writeStringField(eq("monolith_module"), eq("transaction"));
    }

    @Test
    void jsonProviderHandlesPlatformLogger() throws Exception {
        MonolithModuleJsonProvider provider = new MonolithModuleJsonProvider();
        JsonGenerator generator = mock(JsonGenerator.class);
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getLoggerName()).thenReturn("org.springframework.web.servlet.DispatcherServlet");

        provider.writeTo(generator, event);

        verify(generator).writeStringField(eq("monolith_module"), eq("platform"));
    }

    @Test
    void propertiesExposeDefaultsAndSetters() {
        ObservabilityProperties props = new ObservabilityProperties();
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getSlowThreshold()).isEqualTo(Duration.ofMillis(500));

        props.setEnabled(false);
        props.setSlowThreshold(Duration.ofMillis(250));
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getSlowThreshold()).isEqualTo(Duration.ofMillis(250));
    }

    @Test
    void mdcFilterIgnoresPrincipalWithoutUserId() throws Exception {
        UserContextMdcFilter filter = new UserContextMdcFilter();
        com.fastwork.expense.user.User user =
                new com.fastwork.expense.user.User(
                        null, "a@b.c", "hash", com.fastwork.expense.user.UserRole.OWNER);
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(
                        new com.fastwork.expense.user.PetPrincipal(user), null);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        StringBuilder captured = new StringBuilder();
        filter.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                (request, response) -> captured.append(MDC.get(UserContextMdcFilter.MDC_USER_ID)));

        assertThat(captured).hasToString("null");
        assertThat(MDC.get(UserContextMdcFilter.MDC_USER_ID)).isNull();
    }

    @Test
    void mdcFilterSetsUserIdForAuthenticatedPrincipal() throws Exception {
        UserContextMdcFilter filter = new UserContextMdcFilter();
        UUID id = UUID.randomUUID();
        com.fastwork.expense.user.User user =
                new com.fastwork.expense.user.User(
                        id, "a@b.c", "hash", com.fastwork.expense.user.UserRole.OWNER);
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(
                        new com.fastwork.expense.user.PetPrincipal(user), null);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        StringBuilder captured = new StringBuilder();
        filter.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                (request, response) -> captured.append(MDC.get(UserContextMdcFilter.MDC_USER_ID)));

        assertThat(captured).hasToString(id.toString());
        assertThat(MDC.get(UserContextMdcFilter.MDC_USER_ID)).isNull();
    }
}
