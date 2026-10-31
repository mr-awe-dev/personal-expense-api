package com.fastwork.expense.passkey;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Konfigurasi fitur passkey (WebAuthn). Fitur ini opt-in per lingkungan:
 * env WEBAUTHN_ENABLED=true + WEBAUTHN_RP_ID/WEBAUTHN_ALLOWED_ORIGINS.
 * Rp-id harus sama dengan host yang dilihat browser (mis. personal-expense-...pages.dev),
 * karena browser memvalidasi bahwa rp id adalah akhiran domain origin browser.
 */
@Component
@ConfigurationProperties(prefix = "app.webauthn")
public class WebAuthnProperties {

    private boolean enabled = false;
    private String rpId = "";
    private String rpName = "Personal Expense";
    private List<String> allowedOrigins = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRpId() {
        return rpId;
    }

    public void setRpId(String rpId) {
        this.rpId = rpId == null ? "" : rpId.trim();
    }

    public String getRpName() {
        return rpName;
    }

    public void setRpName(String rpName) {
        this.rpName = rpName == null ? "" : rpName.trim();
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins;
    }
}
