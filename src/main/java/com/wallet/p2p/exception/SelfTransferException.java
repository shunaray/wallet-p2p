package com.wallet.p2p.exception;

public class SelfTransferException extends RuntimeException {
    public SelfTransferException(String walletId) {
        super(String.format("Cannot transfer money to the same wallet '%s'", walletId));
    }
}
