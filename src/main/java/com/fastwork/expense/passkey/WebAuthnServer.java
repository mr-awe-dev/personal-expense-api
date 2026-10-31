package com.fastwork.expense.passkey;

import java.util.HashSet;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Component;

import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.RelyingPartyIdentity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Pemegang tunggal instance {@link RelyingParty} Yubico. Instans dibuat malas
 * agar deployment tanpa passkey (fitur mati) tidak pernah menyentuh konfigurasi;
 * saat fitur aktif, konfigurasi tidak valid menggagalkan startup (fail-fast D36).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebAuthnServer {

    private final WebAuthnProperties properties;
    private final PasskeyCredentialRepository credentials;
    private volatile RelyingParty party;

    @PostConstruct
    void validate() {
        if (!properties.isEnabled()) {
            return;
        }
        if (properties.getRpId().isEmpty()) {
            throw new IllegalStateException(
                    "Passkey aktif tetapi WEBAUTHN_RP_ID kosong (harus = host yang dilihat browser)");
        }
        if (properties.getAllowedOrigins().isEmpty()) {
            throw new IllegalStateException(
                    "Passkey aktif tetapi WEBAUTHN_ALLOWED_ORIGINS kosong");
        }
        get();
        log.info(
                "Passkey aktif: rpId={}, origins={}",
                properties.getRpId(),
                properties.getAllowedOrigins());
    }

    public synchronized RelyingParty get() {
        if (party == null) {
            party = build();
        }
        return party;
    }

    private RelyingParty build() {
        RelyingPartyIdentity identity =
                RelyingPartyIdentity.builder()
                        .id(properties.getRpId())
                        .name(properties.getRpName())
                        .build();
        return RelyingParty.builder()
                .identity(identity)
                .credentialRepository(credentials)
                .origins(new HashSet<>(properties.getAllowedOrigins()))
                // Origin pratinjau Cloudflare (subdomain *.pages.dev) diakui otomatis.
                .allowOriginSubdomain(true)
                .build();
    }
}
