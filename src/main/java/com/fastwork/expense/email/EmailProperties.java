package com.fastwork.expense.email;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Konfigurasi pengiriman email (fitur opt-in per lingkungan): env EMAIL_ENABLED + EMAIL_API_KEY +
 * EMAIL_FROM. Kunci API dikirim sebagai header, bukan query; dari = alamat pengirim yang sudah
 * diverifikasi di provider (Brevo). Nilai kosong diperbolehkan hanya saat fitur mati — validasinya
 * dilakukan pabrik bean agar gagal-cepat di startup.
 */
@Component
@ConfigurationProperties(prefix = "app.email")
public class EmailProperties {

    private boolean enabled = false;
    private String apiKey = "";
    private String from = "";
    private String fromName = "Personal Expense";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from == null ? "" : from.trim();
    }

    public String getFromName() {
        return fromName;
    }

    public void setFromName(String fromName) {
        this.fromName = fromName == null ? "" : fromName.trim();
    }
}
