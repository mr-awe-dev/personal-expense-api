package com.fastwork.expense.passkey;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Robo passkey (WebAuthn). Satu baris per kredensial biometrik per perangkat.
 * Menyimpan data minimal yang dibutuhkan RelyingParty Yubico untuk verifikasi
 * assertion: kunci publik COSE, alg, jumlah tanda tangan, dan flag backup.
 */
@Entity
@Table(name = "passkeys")
public class Passkey {

    @Id private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(nullable = false, unique = true, columnDefinition = "bytea")
    private byte[] credentialId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "public_key", nullable = false, columnDefinition = "bytea")
    private byte[] publicKeyCose;

    @Column(nullable = false)
    private Integer alg;

    @Column(nullable = false)
    private String transports = "";

    @Column(name = "signature_count", nullable = false)
    private Long signatureCount = 0L;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(columnDefinition = "bytea")
    private byte[] uvi;

    @Column(name = "backup_eligible", nullable = false)
    private Boolean backupEligible = false;

    @Column(name = "backup_state", nullable = false)
    private Boolean backupState = false;

    @Column(nullable = false)
    private Boolean discoverable = true;

    @Column(nullable = false)
    private String label = "";

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Passkey() {}

    public Passkey(UUID id, UUID userId, byte[] credentialId, byte[] publicKeyCose) {
        this.id = id;
        this.userId = userId;
        this.credentialId = credentialId.clone();
        this.publicKeyCose = publicKeyCose.clone();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public byte[] getCredentialId() {
        return credentialId == null ? null : credentialId.clone();
    }

    public byte[] getPublicKeyCose() {
        return publicKeyCose == null ? null : publicKeyCose.clone();
    }

    public Integer getAlg() {
        return alg;
    }

    public void setAlg(Integer alg) {
        this.alg = alg;
    }

    public String getTransports() {
        return transports;
    }

    public void setTransports(String transports) {
        this.transports = transports == null ? "" : transports;
    }

    public Long getSignatureCount() {
        return signatureCount;
    }

    public void setSignatureCount(Long signatureCount) {
        this.signatureCount = signatureCount;
    }

    public byte[] getUvi() {
        return uvi == null ? null : uvi.clone();
    }

    public void setUvi(byte[] uvi) {
        this.uvi = uvi;
    }

    public Boolean getBackupEligible() {
        return backupEligible;
    }

    public void setBackupEligible(Boolean backupEligible) {
        this.backupEligible = backupEligible;
    }

    public Boolean getBackupState() {
        return backupState;
    }

    public void setBackupState(Boolean backupState) {
        this.backupState = backupState;
    }

    public Boolean getDiscoverable() {
        return discoverable;
    }

    public void setDiscoverable(Boolean discoverable) {
        this.discoverable = discoverable;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label == null ? "" : label;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
