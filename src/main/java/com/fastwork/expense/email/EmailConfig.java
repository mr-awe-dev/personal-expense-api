package com.fastwork.expense.email;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Pabrik implementasi {@link EmailSender}: Brevo saat {@code app.email.enabled=true} (fail-fast:
 * api-key + from wajib diisi), dan implementasi penolak saat mati (default, juga dipakai profil
 * test/openapi/cli lewat stub nilai profil).
 */
@Configuration
public class EmailConfig {

    @Bean
    @ConditionalOnProperty(prefix = "app.email", name = "enabled", havingValue = "true")
    public EmailSender brevoEmailSender(EmailProperties properties, RestClient.Builder builder) {
        if (!StringUtils.hasText(properties.getApiKey())
                || !StringUtils.hasText(properties.getFrom())) {
            throw new IllegalStateException(
                    "app.email.enabled=true menuntut EMAIL_API_KEY dan EMAIL_FROM terisi");
        }
        return new BrevoEmailSender(
                builder, properties.getApiKey(), properties.getFrom(), properties.getFromName());
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "app.email",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true)
    public EmailSender noopEmailSender() {
        return new NoopEmailSender();
    }
}
