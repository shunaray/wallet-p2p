package com.wallet.p2p.service;

import com.wallet.p2p.domain.entity.Wallet;
import com.wallet.p2p.exception.WalletNotFoundException;
import com.wallet.p2p.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository walletRepository;
    private final WalletCreationHelper walletCreationHelper;
    private final MetricsService metricsService;
    private final LogEventService logEventService;

    public WalletService(WalletRepository walletRepository,
                         WalletCreationHelper walletCreationHelper,
                         MetricsService metricsService,
                         LogEventService logEventService) {
        this.walletRepository = walletRepository;
        this.walletCreationHelper = walletCreationHelper;
        this.metricsService = metricsService;
        this.logEventService = logEventService;
    }

    /**
     * Atomically get-or-create a wallet for a user.
     *
     * <p>Concurrency strategy:
     * <ol>
     *   <li>Fast-path read — if the wallet already exists, return immediately.</li>
     *   <li>Best-effort INSERT via a REQUIRES_NEW inner transaction so that a
     *       constraint collision on the unique {@code user_id} index never
     *       marks <em>this</em> outer transaction as rollback-only.</li>
     *   <li>Final authoritative read — whoever won the race, we return the
     *       single wallet that now exists in the DB.</li>
     * </ol>
     */
    public Wallet getOrCreateWallet(String userId, Long initialBalancePaise) {
        long initialBalance = (initialBalancePaise != null) ? initialBalancePaise : 0L;

        // Fast path: wallet already exists
        Optional<Wallet> existing = walletRepository.findByUserId(userId);
        if (existing.isPresent()) {
            return existing.get();
        }

        String walletId = "wal_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // Attempt insert in an isolated REQUIRES_NEW transaction so any
        // constraint violation is contained and does NOT mark our tx rollback-only.
        boolean inserted = walletCreationHelper.tryInsert(walletId, userId, initialBalance);

        if (inserted) {
            MDC.put("wallet_id", walletId);
            MDC.put("event_type", "wallet_created");
            log.info("Wallet created successfully for user {} with initial balance {} paise", userId, initialBalance);
            MDC.remove("event_type");
            metricsService.incrementWalletsCreated();

            java.util.Map<String, Object> eventData = new java.util.HashMap<>();
            eventData.put("user_id", userId);
            eventData.put("wallet_id", walletId);
            eventData.put("initial_balance_paise", initialBalance);
            logEventService.recordDomainEvent("wallet_created", eventData);
        }

        // Final authoritative read — returns whichever thread won the race.
        return walletRepository.findByUserId(userId)
                .orElseGet(() -> {
                    // Last-resort fallback for environments where native query is a no-op
                    try {
                        Wallet newWallet = new Wallet(walletId, userId, initialBalance);
                        Wallet saved = walletRepository.save(newWallet);
                        metricsService.incrementWalletsCreated();
                        return saved;
                    } catch (DataIntegrityViolationException dive) {
                        return walletRepository.findByUserId(userId)
                                .orElseThrow(() -> new IllegalStateException("Failed to retrieve or create wallet"));
                    }
                });
    }

    @Transactional(readOnly = true)
    public Wallet getWallet(String walletId) {
        return walletRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));
    }

    @Transactional(readOnly = true)
    public Wallet getWalletByUserId(String userId) {
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("user:" + userId));
    }
}
