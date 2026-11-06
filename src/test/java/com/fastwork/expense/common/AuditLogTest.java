package com.fastwork.expense.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Unit test masking email dan resolusi IP klien untuk log audit. */
class AuditLogTest {

    @Test
    void maskEmailTanpaAtNullMenjadiPenuh() {
        assertThat(AuditLog.maskEmail(null)).isEqualTo("***");
        assertThat(AuditLog.maskEmail("")).isEqualTo("***");
        assertThat(AuditLog.maskEmail("bukan-email")).isEqualTo("***");
    }

    @Test
    void maskEmailLokalSatuKarakter() {
        assertThat(AuditLog.maskEmail("a@fastwork.co")).isEqualTo("***@fastwork.co");
    }

    @Test
    void maskEmailStandarSisipkanAwalan() {
        assertThat(AuditLog.maskEmail("admin@fastwork.co")).isEqualTo("a***@fastwork.co");
        assertThat(AuditLog.maskEmail("Nama.Panjang@example.com"))
                .isEqualTo("N***@example.com");
    }

    @Test
    void clientIpAmbilHopPertamaXForwardedFor() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.2");
        assertThat(AuditLog.clientIp(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void clientIpFallbackPadaXRealIp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Real-IP", "198.51.100.4");
        assertThat(AuditLog.clientIp(request)).isEqualTo("198.51.100.4");
    }

    @Test
    void clientIpFallbackKeRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        assertThat(AuditLog.clientIp(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void clientIpHeaderKosongDiabaikan() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "   ");
        request.setRemoteAddr("192.0.2.9");
        assertThat(AuditLog.clientIp(request)).isEqualTo("192.0.2.9");
    }
}
