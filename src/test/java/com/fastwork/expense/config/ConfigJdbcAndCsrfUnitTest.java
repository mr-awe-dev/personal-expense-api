package com.fastwork.expense.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.Types;

import org.hibernate.type.descriptor.ValueBinder;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.StringJavaType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

/** Unit test binder parameter "unknown" dan handler CSRF SPA. */
class ConfigJdbcAndCsrfUnitTest {

    @Test
    void citextBinderSendsOpaqueParameters() throws Exception {
        assertOpaqueBinder(new CitextJdbcType());
    }

    @Test
    void enumBinderSendsOpaqueParameters() throws Exception {
        assertOpaqueBinder(new PostgresEnumJdbcType());
    }

    @Test
    void opaqueTypeReportsOtherJdbcCode() {
        assertThat(new CitextJdbcType().getJdbcTypeCode()).isEqualTo(Types.OTHER);
        assertThat(new PostgresEnumJdbcType().getJdbcTypeCode()).isEqualTo(Types.OTHER);
    }

    private void assertOpaqueBinder(PostgresOpaqueTextJdbcType type) throws Exception {
        ValueBinder<String> binder = type.getBinder(StringJavaType.INSTANCE);
        PreparedStatement statement = mock(PreparedStatement.class);
        CallableStatement callable = mock(CallableStatement.class);
        WrapperOptions options = mock(WrapperOptions.class);

        binder.bind(statement, "owner", 1, options);
        verify(statement).setObject(1, "owner", Types.OTHER);

        binder.bind(callable, "income", "pType", options);
        verify(callable).setObject("pType", "income", Types.OTHER);
    }

    @Test
    void spaHandlerPrefersRawHeaderThenFallsBackToParameter() {
        SpaCsrfTokenRequestHandler handler = new SpaCsrfTokenRequestHandler();
        CsrfToken token = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "raw-token");

        MockHttpServletRequest withHeader = new MockHttpServletRequest();
        withHeader.addHeader("X-XSRF-TOKEN", "header-token");
        assertThat(handler.resolveCsrfTokenValue(withHeader, token)).isEqualTo("header-token");

        MockHttpServletRequest blankHeader = new MockHttpServletRequest();
        blankHeader.addHeader("X-XSRF-TOKEN", "   ");
        // header kosong didelegasikan ke super — CsrfTokenRequestAttributeHandler 6.5
        // mengembalikan header non-null apa adanya bila parameter tidak ada
        assertThat(handler.resolveCsrfTokenValue(blankHeader, token)).isEqualTo("   ");
        blankHeader.setParameter("_csrf", "param-token");
        assertThat(handler.resolveCsrfTokenValue(blankHeader, token)).isEqualTo("   ");

        MockHttpServletRequest parameter = new MockHttpServletRequest();
        parameter.setParameter("_csrf", "param-token");
        assertThat(handler.resolveCsrfTokenValue(parameter, token)).isEqualTo("param-token");
    }

    @Test
    void spaHandlerHandleDelegatesToSuper() {
        SpaCsrfTokenRequestHandler handler = new SpaCsrfTokenRequestHandler();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        DefaultCsrfToken token = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "t");
        handler.handle(request, response, () -> token);
        assertThat(request.getAttribute(CsrfToken.class.getName())).isNotNull();
    }

    @Test
    void csrfCookieFilterToleratesMissingTokenAttribute() throws Exception {
        Class<?> filterClass =
                Class.forName("com.fastwork.expense.config.SecurityConfig$CsrfCookieFilter");
        Constructor<?> constructor = filterClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object filter = constructor.newInstance();

        Method doFilter =
                filterClass.getDeclaredMethod(
                        "doFilterInternal",
                        jakarta.servlet.http.HttpServletRequest.class,
                        jakarta.servlet.http.HttpServletResponse.class,
                        jakarta.servlet.FilterChain.class);
        doFilter.setAccessible(true);

        // Tanpa atribut CsrfToken sama sekali → cabang null harus tetap menjalankan chain.
        MockFilterChain chain = new MockFilterChain();
        doFilter.invoke(filter, new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void cspDirectivesExpandConnectSrcOnlyWhenBackendUrlIsSet() {
        // backend-url kosong → satu origin saja (proxy nginx/Caddy menangani /api).
        assertThat(SecurityConfig.cspDirectives("")).endsWith("connect-src 'self';");

        String withBackend = SecurityConfig.cspDirectives("https://api.example.com");
        assertThat(withBackend).contains("connect-src 'self' https://api.example.com;");
        assertThat(withBackend).contains("default-src 'self';");
    }
}
