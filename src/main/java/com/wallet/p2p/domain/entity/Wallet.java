package com.wallet.p2p.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "wallets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Wallet {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "user_id", length = 64, nullable = false, unique = true)
    private String userId;

    @Builder.Default
    @Column(name = "balance_paise", nullable = false)
    private Long balancePaise = 0L;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Wallet(String id, String userId, Long balancePaise) {
        this.id = id;
        this.userId = userId;
        this.balancePaise = balancePaise != null ? balancePaise : 0L;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void credit(long amountPaise) {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("Credit amount must be positive paise");
        }
        this.balancePaise += amountPaise;
        this.updatedAt = Instant.now();
    }

    public boolean canDebit(long amountPaise) {
        return this.balancePaise >= amountPaise;
    }

    public void debit(long amountPaise) {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("Debit amount must be positive paise");
        }
        if (this.balancePaise < amountPaise) {
            throw new IllegalStateException("Insufficient balance to debit " + amountPaise + " paise");
        }
        this.balancePaise -= amountPaise;
        this.updatedAt = Instant.now();
    }
}
