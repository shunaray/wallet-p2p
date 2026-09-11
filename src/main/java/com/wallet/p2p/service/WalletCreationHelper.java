package com.wallet.p2p.service;

import com.wallet.p2p.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Helper that runs the upsert INSERT in its own independent transaction
 * (REQUIRES_NEW). This prevents a constraint-violation from the
 * ON CONFLICT DO NOTHING clause from marking the caller's outer
 * transaction as rollback-only when running against H2 in test mode.
 */
@Service
public class WalletCreationHelper {

    private static final Logger log = LoggerFactory.getLogger(WalletCreationHelper.class);

    private final WalletRepository walletRepository;

    public WalletCreationHelper(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    /**
     * Attempts a best-effort INSERT with ON CONFLICT DO NOTHING.
     * Returns true if the row was inserted, false if it already existed.
     * Any DataIntegrityViolationException (e.g. H2 in PostgreSQL mode)
     * is swallowed here so the caller's transaction stays healthy.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryInsert(String walletId, String userId, long balancePaise) {
        try {
            int rows = walletRepository.insertIfNotExists(walletId, userId, balancePaise);
            return rows > 0;
        } catch (Exception e) {
            // Concurrent insert raced us — row already exists; this is expected.
            log.debug("Concurrent wallet insert collision for user {}: {}", userId, e.getMessage());
            return false;
        }
    }
}
