package com.fastwork.expense.passkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Validasi konfigurasi WebAuthn saat startup (fail-fast D36). */
class WebAuthnServerTest {

    private static WebAuthnProperties properties(boolean enabled, String rpId, String... origins) {
        WebAuthnProperties properties = new WebAuthnProperties();
        properties.setEnabled(enabled);
        properties.setRpId(rpId);
        properties.setAllowedOrigins(new ArrayList<>(List.of(origins)));
        return properties;
    }

    private static WebAuthnServer server(WebAuthnProperties properties) {
        return new WebAuthnServer(properties, new PasskeyCredentialRepository(null, null));
    }

    @Test
    void disabledPropertiesSkipValidation() {
        WebAuthnServer server = server(properties(false, "", "http://localhost:5173"));
        server.validate();
        assertThat(server).isNotNull();
    }

    @Test
    void enabledWithoutRpIdFailsFast() {
        WebAuthnServer server = server(properties(true, "", "http://localhost:5173"));
        assertThatThrownBy(server::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEBAUTHN_RP_ID");
    }

    @Test
    void enabledWithoutOriginsFailsFast() {
        WebAuthnServer server = server(properties(true, "localhost"));
        assertThatThrownBy(server::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEBAUTHN_ALLOWED_ORIGINS");
    }

    @Test
    void enabledBuildsAndCachesRelyingParty() {
        WebAuthnServer server = server(properties(true, " localhost ", "http://localhost:5173"));

        server.validate();
        assertThat(server.get()).isSameAs(server.get());
        assertThat(server.get().getIdentity().getId()).isEqualTo("localhost");
    }

    @Test
    void propertiesTrimAndDefaultEmptyValues() {
        WebAuthnProperties properties = new WebAuthnProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getRpName()).isEqualTo("Personal Expense");
        assertThat(properties.getAllowedOrigins()).isEmpty();

        properties.setRpId(null);
        properties.setRpName(null);
        properties.setAllowedOrigins(null);
        assertThat(properties.getRpId()).isEmpty();
        assertThat(properties.getRpName()).isEmpty();
        assertThat(properties.getAllowedOrigins()).isEmpty();
    }
}
