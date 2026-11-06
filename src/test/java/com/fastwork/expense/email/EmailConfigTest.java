package com.fastwork.expense.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Pabrik implementasi EmailSender: fail-fast kredensial + pilihan noop/Brevo. */
class EmailConfigTest {

    private final EmailConfig config = new EmailConfig();

    private EmailProperties properties(boolean enabled, String apiKey, String from) {
        EmailProperties properties = new EmailProperties();
        properties.setEnabled(enabled);
        properties.setApiKey(apiKey);
        properties.setFrom(from);
        return properties;
    }

    @Test
    void enabledPropertiesCreateBrevoSender() {
        EmailSender sender =
                config.brevoEmailSender(
                        properties(true, "kunci", "pengirim@contoh.com"), RestClient.builder());
        assertThat(sender).isInstanceOf(BrevoEmailSender.class);
    }

    @Test
    void enabledWithoutKeyFailsFast() {
        assertThatThrownBy(
                        () ->
                                config.brevoEmailSender(
                                        properties(true, "   ", "pengirim@contoh.com"),
                                        RestClient.builder()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enabledWithoutFromFailsFast() {
        assertThatThrownBy(
                        () ->
                                config.brevoEmailSender(
                                        properties(true, "kunci", "   "), RestClient.builder()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void disabledPropertiesReturnNoopSender() {
        assertThat(config.noopEmailSender()).isInstanceOf(NoopEmailSender.class);
    }
}
