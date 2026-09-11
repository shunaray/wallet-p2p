package com.wallet.p2p.exception;

import lombok.Getter;

@Getter
public class IdempotencyConflictException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyConflictException(String idempotencyKey) {
        super(String.format("Idempotency key '%s' has already been used with a different request payload", idempotencyKey));
        this.idempotencyKey = idempotencyKey;
    }
}
