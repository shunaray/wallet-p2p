package com.wallet.p2p.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wallet.p2p.domain.entity.Wallet;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WalletResponse {

    @JsonProperty("wallet_id")
    private String walletId;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("balance_paise")
    private Long balancePaise;

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("updated_at")
    private Instant updatedAt;

    public static WalletResponse fromEntity(Wallet wallet) {
        return WalletResponse.builder()
                .walletId(wallet.getId())
                .userId(wallet.getUserId())
                .balancePaise(wallet.getBalancePaise())
                .createdAt(wallet.getCreatedAt())
                .updatedAt(wallet.getUpdatedAt())
                .build();
    }
}
