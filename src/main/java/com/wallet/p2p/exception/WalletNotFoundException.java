package com.wallet.p2p.exception;

public class WalletNotFoundException extends RuntimeException {
    public WalletNotFoundException(String walletId) {
        super(String.format("Wallet '%s' was not found", walletId));
    }
}
