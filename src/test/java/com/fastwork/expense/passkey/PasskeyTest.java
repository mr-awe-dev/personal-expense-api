package com.fastwork.expense.passkey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Pemetaan entitas passkey: clone defensif array biner dan default kolom. */
class PasskeyTest {

    @Test
    void constructorClonesBinaryColumns() {
        byte[] credentialId = {1, 2, 3};
        byte[] publicKey = {4, 5, 6};
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        Passkey row = new Passkey(id, userId, credentialId, publicKey);
        credentialId[0] = 9;
        publicKey[0] = 9;

        assertThat(row.getId()).isEqualTo(id);
        assertThat(row.getUserId()).isEqualTo(userId);
        assertThat(row.getCredentialId()).containsExactly(1, 2, 3);
        assertThat(row.getPublicKeyCose()).containsExactly(4, 5, 6);
        assertThat(row.getTransports()).isEmpty();
        assertThat(row.getLabel()).isEmpty();
        assertThat(row.getSignatureCount()).isZero();
        assertThat(row.getBackupEligible()).isFalse();
        assertThat(row.getBackupState()).isFalse();
        assertThat(row.getDiscoverable()).isTrue();
    }

    @Test
    void entityWithoutValuesReturnsNullBinaryColumns() {
        Passkey row = new Passkey();

        assertThat(row.getCredentialId()).isNull();
        assertThat(row.getPublicKeyCose()).isNull();
        assertThat(row.getUvi()).isNull();
        assertThat(row.getAlg()).isNull();
    }

    @Test
    void mutatorsStoreValuesAndNullDefaults() {
        Passkey row = new Passkey();
        byte[] uvi = {7, 8};

        row.setAlg(-7);
        row.setUvi(uvi);
        row.setLabel("iPhone");
        row.setTransports("usb,nfc");
        row.setSignatureCount(3L);
        row.setBackupEligible(true);
        row.setBackupState(true);
        row.setDiscoverable(false);

        assertThat(row.getAlg()).isEqualTo(-7);
        assertThat(row.getUvi()).containsExactly(7, 8);
        assertThat(row.getLabel()).isEqualTo("iPhone");
        assertThat(row.getTransports()).isEqualTo("usb,nfc");
        assertThat(row.getSignatureCount()).isEqualTo(3L);
        assertThat(row.getBackupEligible()).isTrue();
        assertThat(row.getBackupState()).isTrue();
        assertThat(row.getDiscoverable()).isFalse();

        row.setLabel(null);
        row.setTransports(null);
        assertThat(row.getLabel()).isEmpty();
        assertThat(row.getTransports()).isEmpty();
    }
}
