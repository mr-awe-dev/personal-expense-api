package com.fastwork.expense.email;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fastwork.expense.common.ApiException;
import com.fastwork.expense.common.AuditLog;

/**
 * Pengirim email lewat REST API Brevo {@code POST /v3/smtp/email}. Dibuat hanya bila
 * {@code app.email.enabled=true} — lihat {@code EmailConfig}. Kunci API tidak pernah di-log dan
 * gagal kirim dipetakan ke 503 agar antarmuka reset bisa menampilkan pesan yang jelas.
 */
public class BrevoEmailSender implements EmailSender {

    static final String API_URL = "https://api.brevo.com/v3/smtp/email";

    private static final Logger log = LoggerFactory.getLogger(BrevoEmailSender.class);

    private final RestClient restClient;
    private final String from;
    private final String fromName;

    public BrevoEmailSender(RestClient.Builder builder, String apiKey, String from, String fromName) {
        this.restClient =
                builder
                        .baseUrl(API_URL)
                        .defaultHeader("api-key", apiKey)
                        .defaultHeader("accept", "application/json")
                        .defaultHeader("content-type", MediaType.APPLICATION_JSON_VALUE)
                        .build();
        this.from = from;
        this.fromName = fromName.isBlank() ? from : fromName;
    }

    @Override
    public void send(EmailMessage message) {
        try {
            restClient.post().body(brevoBody(message)).retrieve().toBodilessEntity();
        } catch (RestClientResponseException ex) {
            // Status HTTP provider — jangan cetak body penuh agar antara lain kode tidak bocor.
            log.error(
                    "Brevo menolak email: status={}, to={}",
                    ex.getStatusCode().value(),
                    AuditLog.maskEmail(message.to()));
            throw ApiException.serviceUnavailable("Gagal mengirim email kode pemulihan");
        } catch (RestClientException ex) {
            log.error(
                    "Brevo tidak dapat dihubungi: to={}", AuditLog.maskEmail(message.to()), ex);
            throw ApiException.serviceUnavailable("Gagal mengirim email kode pemulihan");
        }
    }

    private Map<String, Object> brevoBody(EmailMessage message) {
        return Map.of(
                "sender", Map.of("email", from, "name", fromName),
                "to", List.of(Map.of("email", message.to())),
                "subject", message.subject(),
                "textContent", message.text());
    }
}
