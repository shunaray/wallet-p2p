package com.wallet.p2p.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "transfers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Transfer {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "from_wallet_id", length = 64, nullable = false)
    private String fromWalletId;

    @Column(name = "to_wallet_id", length = 64, nullable = false)
    private String toWalletId;

    @Column(name = "amount_paise", nullable = false)
    private Long amountPaise;

    @Column(name = "idempotency_key", length = 128, nullable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private TransferStatus status;

    @Column(name = "error_reason")
    private String errorReason;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Transfer(String id, String fromWalletId, String toWalletId, Long amountPaise,
                    String idempotencyKey, TransferStatus status, String errorReason) {
        this.id = id;
        this.fromWalletId = fromWalletId;
        this.toWalletId = toWalletId;
        this.amountPaise = amountPaise;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.errorReason = errorReason;
        this.createdAt = Instant.now();
    }
}
