package com.fastwork.expense.category;

import java.time.Instant;
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
@Table(name = "categories")
public class Category {

    @Id private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JdbcType(PostgresEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "tx_type")
    private TxType type;

    @Column(nullable = false)
    private String name;

    @Column(name = "name_key", nullable = false)
    private String nameKey;

    @Column(nullable = false)
    private Boolean archived = Boolean.FALSE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Category() {}

    public Category(UUID id, UUID userId, TxType type, String name, String nameKey) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.name = name;
        this.nameKey = nameKey;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public TxType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public String getNameKey() {
        return nameKey;
    }

    public void rename(String name, String nameKey) {
        this.name = name;
        this.nameKey = nameKey;
    }

    public Boolean getArchived() {
        return archived;
    }

    public void setArchived(Boolean archived) {
        this.archived = archived;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
