package com.fastwork.expense.transaction;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.UpdateTimestamp;

import com.fastwork.expense.common.TxType;
import com.fastwork.expense.config.PostgresEnumJdbcType;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private LocalDate date;

    @JdbcType(PostgresEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "tx_type")
    private TxType type;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(nullable = false)
    private String description = "";

    @Column(nullable = false)
    private long amount;

    @Column(name = "closing_id")
    private UUID closingId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Transaction() {}

    public Transaction(UUID id, UUID userId, LocalDate date, TxType type, long amount) {
        this.id = id;
        this.userId = userId;
        this.date = date;
        this.type = type;
        this.amount = amount;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public TxType getType() {
        return type;
    }

    public void setType(TxType type) {
        this.type = type;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }

    public UUID getClosingId() {
        return closingId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
