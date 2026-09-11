package com.wallet.p2p.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wallet.p2p.domain.entity.Transfer;
import com.wallet.p2p.domain.entity.TransferStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransferResponse {

    @JsonProperty("transfer_id")
    private String transferId;

    @JsonProperty("from_wallet_id")
    private String fromWalletId;

    @JsonProperty("to_wallet_id")
    private String toWalletId;

    @JsonProperty("amount_paise")
    private Long amountPaise;

    @JsonProperty("idempotency_key")
    private String idempotencyKey;

    @JsonProperty("status")
    private TransferStatus status;

    @JsonProperty("error_reason")
    private String errorReason;

    @JsonProperty("created_at")
    private Instant createdAt;

    public static TransferResponse fromEntity(Transfer transfer) {
        return TransferResponse.builder()
                .transferId(transfer.getId())
                .fromWalletId(transfer.getFromWalletId())
                .toWalletId(transfer.getToWalletId())
                .amountPaise(transfer.getAmountPaise())
                .idempotencyKey(transfer.getIdempotencyKey())
                .status(transfer.getStatus())
                .errorReason(transfer.getErrorReason())
                .createdAt(transfer.getCreatedAt())
                .build();
    }
}
