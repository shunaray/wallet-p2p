package com.wallet.p2p.exception;

public class TransferNotFoundException extends RuntimeException {
    public TransferNotFoundException(String transferId) {
        super(String.format("Transfer '%s' was not found", transferId));
    }
}
