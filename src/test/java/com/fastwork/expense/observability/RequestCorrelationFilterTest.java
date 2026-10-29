package com.fastwork.expense.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    private MockHttpServletResponse run(MockHttpServletRequest request, String inboundHeader)
            throws Exception {
        if (inboundHeader != null) {
            request.addHeader(RequestCorrelationFilter.HEADER, inboundHeader);
        }
        AtomicReference<String> during = new AtomicReference<>();
        FilterChain chain =
                (req, res) -> during.set(MDC.get(RequestCorrelationFilter.MDC_REQUEST_ID));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(during.get()).isNotNull();
        return response;
    }

    @Test
    void generatesRequestIdWhenAbsent() throws Exception {
        MockHttpServletResponse response =
                run(new MockHttpServletRequest("GET", "/api/v1/me"), null);
        String id = response.getHeader(RequestCorrelationFilter.HEADER);
        assertThat(id).matches("[0-9a-f]{32}");
    }

    @Test
    void acceptsValidInboundId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        MockHttpServletResponse response = run(request, "abc_123-XYZ");
        assertThat(response.getHeader(RequestCorrelationFilter.HEADER)).isEqualTo("abc_123-XYZ");
    }

    @Test
    void rejectsInvalidInboundIdAndReplacesIt() throws Exception {
        for (String bad :
                new String[] {
                    "",
                    "ada spasi",
                    "'; DROP TABLE users; --",
                    "x".repeat(65),
                    "satu.dot",
                    "emoji🙂"
                }) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
            MockHttpServletResponse response = run(request, bad);
            String echoed = response.getHeader(RequestCorrelationFilter.HEADER);
            assertThat(echoed).isNotEqualTo(bad);
            assertThat(echoed).matches("[0-9a-f]{32}");
        }
    }

    @Test
    void mdcIsCleanedUpAfterChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        run(request, null);
        assertThat(MDC.get(RequestCorrelationFilter.MDC_REQUEST_ID)).isNull();
    }

    @Test
    void mdcIsCleanedUpEvenWhenChainThrows() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain throwing =
                (req, res) -> {
                    throw new IOException("boom");
                };
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> filter.doFilter(request, response, throwing))
                .isInstanceOf(IOException.class);
        assertThat(MDC.get(RequestCorrelationFilter.MDC_REQUEST_ID)).isNull();
    }
}
