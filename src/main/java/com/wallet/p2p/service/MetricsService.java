package com.wallet.p2p.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class MetricsService {

    private final Counter transfersCreatedCounter;
    private final Counter transfersDeclinedInsufficientFundsCounter;
    private final Counter idempotentReplaysCounter;
    private final Counter walletsCreatedCounter;
    private final Timer transferProcessingTimer;

    public MetricsService(MeterRegistry registry) {
        this.transfersCreatedCounter = Counter.builder("transfers.created")
                .description("Total number of successful transfers created")
                .tag("status", "COMPLETED")
                .register(registry);

        this.transfersDeclinedInsufficientFundsCounter = Counter.builder("transfers.declined")
                .description("Total number of transfers declined due to insufficient funds")
                .tag("reason", "INSUFFICIENT_FUNDS")
                .register(registry);

        this.idempotentReplaysCounter = Counter.builder("transfers.idempotent_replays")
                .description("Total number of idempotent transfer replays served")
                .tag("result", "HIT")
                .register(registry);

        this.walletsCreatedCounter = Counter.builder("wallets.created")
                .description("Total number of wallets created")
                .register(registry);

        this.transferProcessingTimer = Timer.builder("transfers.processing.duration")
                .description("Latency distribution for transfer execution")
                .publishPercentiles(0.5, 0.9, 0.99)
                .register(registry);
    }

    public void incrementTransfersCreated() {
        transfersCreatedCounter.increment();
    }

    public void incrementTransfersDeclined() {
        transfersDeclinedInsufficientFundsCounter.increment();
    }

    public void incrementIdempotentReplays() {
        idempotentReplaysCounter.increment();
    }

    public void incrementWalletsCreated() {
        walletsCreatedCounter.increment();
    }

    public void recordTransferDuration(long durationNanos) {
        transferProcessingTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }
}
