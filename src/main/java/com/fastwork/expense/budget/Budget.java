package com.fastwork.expense.budget;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.UpdateTimestamp;

import com.fastwork.expense.config.PostgresEnumJdbcType;

@Entity
@Table(name = "budgets")
public class Budget {

    @Id private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @JdbcType(PostgresEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "budget_period")
    private BudgetPeriod period;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private String description = "";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Budget() {}

    public Budget(
            UUID id,
            UUID userId,
            UUID categoryId,
            BudgetPeriod period,
            long amount,
            String description) {
        this.id = id;
        this.userId = userId;
        this.categoryId = categoryId;
        this.period = period;
        this.amount = amount;
        this.description = description;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public BudgetPeriod getPeriod() {
        return period;
    }

    public void setPeriod(BudgetPeriod period) {
        this.period = period;
    }

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
