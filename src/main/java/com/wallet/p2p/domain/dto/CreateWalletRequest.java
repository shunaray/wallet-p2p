package com.wallet.p2p.domain.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateWalletRequest {

    @JsonProperty("user_id")
    @JsonAlias({"userId", "user"})
    private String userId;

    @Builder.Default
    @PositiveOrZero(message = "Initial balance must be zero or positive integer paise")
    @JsonProperty("initial_balance_paise")
    @JsonAlias({"initialBalancePaise", "balance_paise", "initial_balance"})
    private Long initialBalancePaise = 0L;
}
