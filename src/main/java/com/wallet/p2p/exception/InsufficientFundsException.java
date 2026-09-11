package com.wallet.p2p.exception;

import lombok.Getter;

@Getter
public class InsufficientFundsException extends RuntimeException {

    private final String walletId;
    private final long currentBalancePaise;
    private final long requestedAmountPaise;

    public InsufficientFundsException(String walletId, long currentBalancePaise, long requestedAmountPaise) {
        super(String.format("Wallet '%s' has insufficient balance (%d paise) for transfer of %d paise",
                walletId, currentBalancePaise, requestedAmountPaise));
        this.walletId = walletId;
        this.currentBalancePaise = currentBalancePaise;
        this.requestedAmountPaise = requestedAmountPaise;
    }
}
