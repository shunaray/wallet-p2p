package com.wallet.p2p.domain.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateTransferRequest {

    @NotBlank(message = "from_wallet_id is required")
    @JsonProperty("from_wallet_id")
    @JsonAlias({"from", "from_wallet_id", "fromWalletId"})
    private String fromWalletId;

    @NotBlank(message = "to_wallet_id is required")
    @JsonProperty("to_wallet_id")
    @JsonAlias({"to", "to_wallet_id", "toWalletId"})
    private String toWalletId;

    @NotNull(message = "amount_paise is required")
    @Positive(message = "amount_paise must be positive integer paise")
    @JsonProperty("amount_paise")
    @JsonAlias({"amount_paise", "amountPaise", "amount"})
    private Long amountPaise;

    @NotBlank(message = "idempotency_key is required")
    @JsonProperty("idempotency_key")
    @JsonAlias({"idempotency_key", "idempotencyKey", "key"})
    private String idempotencyKey;
}
