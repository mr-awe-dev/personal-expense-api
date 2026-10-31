package com.fastwork.expense.passkey;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** Daftar passkey milik pengguna (kartu "Passkey" di Pengaturan). */
@Schema(name = "Passkey")
public record PasskeyDto(
        UUID id,
        String label,
        @Schema(example = "true") boolean backedUp,
        Instant createdAt,
        Instant lastUsedAt) {

    public static PasskeyDto from(Passkey passkey, String fallbackLabel) {
        String label = passkey.getLabel().isBlank() ? fallbackLabel : passkey.getLabel();
        return new PasskeyDto(
                passkey.getId(),
                label,
                passkey.getBackupState(),
                passkey.getCreatedAt(),
                passkey.getLastUsedAt());
    }
}
