package com.wallet.p2p.service;

import com.wallet.p2p.config.SecurityUserContext;
import com.wallet.p2p.domain.dto.CreateTransferRequest;
import com.wallet.p2p.domain.dto.TransferResponse;
import com.wallet.p2p.domain.entity.IdempotencyRecord;
import com.wallet.p2p.domain.entity.Transfer;
import com.wallet.p2p.domain.entity.TransferStatus;
import com.wallet.p2p.domain.entity.Wallet;
import com.wallet.p2p.exception.InsufficientFundsException;
import com.wallet.p2p.exception.SelfTransferException;
import com.wallet.p2p.exception.TransferNotFoundException;
import com.wallet.p2p.exception.UnauthorizedException;
import com.wallet.p2p.exception.WalletNotFoundException;
import com.wallet.p2p.repository.TransferRepository;
import com.wallet.p2p.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final IdempotencyService idempotencyService;
    private final MetricsService metricsService;
    private final LogEventService logEventService;

    public TransferService(WalletRepository walletRepository,
                           TransferRepository transferRepository,
                           IdempotencyService idempotencyService,
                           MetricsService metricsService,
                           LogEventService logEventService) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.idempotencyService = idempotencyService;
        this.metricsService = metricsService;
        this.logEventService = logEventService;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
    public TransferResponse executeTransfer(CreateTransferRequest request) {
        long startTime = System.nanoTime();
        String fromId = request.getFromWalletId();
        String toId = request.getToWalletId();
        long amountPaise = request.getAmountPaise();
        String idempotencyKey = request.getIdempotencyKey();

        if (fromId.equals(toId)) {
            throw new SelfTransferException(fromId);
        }

        String requestHash = idempotencyService.computeRequestHash(request);

        // Step 1: Idempotency Check
        Optional<IdempotencyRecord> cachedOpt = idempotencyService.checkIdempotency(idempotencyKey, requestHash);
        if (cachedOpt.isPresent()) {
            IdempotencyRecord cached = cachedOpt.get();
            MDC.put("event_type", "idempotent_replay_hit");
            log.info("Idempotent replay served for key: {}", idempotencyKey);
            MDC.remove("event_type");
            metricsService.incrementIdempotentReplays();

            java.util.Map<String, Object> replayData = new java.util.HashMap<>();
            replayData.put("idempotency_key", idempotencyKey);
            replayData.put("transfer_id", cached.getTransferId());
            replayData.put("status", cached.getResponseStatus());
            logEventService.recordDomainEvent("idempotent_replay_hit", replayData);

            return idempotencyService.deserializeResponseBody(cached.getResponseBody(), TransferResponse.class);
        }

        // Step 2: Deterministic Row-Locking Order to Mathematically Prevent Deadlocks
        // Always lock in sorted order of wallet IDs: min(A, B) first, then max(A, B)
        String firstId = fromId.compareTo(toId) < 0 ? fromId : toId;
        String secondId = fromId.compareTo(toId) < 0 ? toId : fromId;

        Wallet firstWallet = walletRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new WalletNotFoundException(firstId));
        Wallet secondWallet = walletRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new WalletNotFoundException(secondId));

        // Step 2b: Post-Lock Idempotency Double-Check
        // When concurrent identical requests queue behind row locks, the next thread acquires the lock
        // AFTER the first thread committed. Re-checking idempotency guarantees immediate return with zero double-debit.
        Optional<IdempotencyRecord> postLockOpt = idempotencyService.checkIdempotency(idempotencyKey, requestHash);
        if (postLockOpt.isPresent()) {
            IdempotencyRecord cached = postLockOpt.get();
            MDC.put("event_type", "idempotent_replay_hit");
            log.info("Idempotent replay served post-lock for key: {}", idempotencyKey);
            MDC.remove("event_type");
            metricsService.incrementIdempotentReplays();
            return idempotencyService.deserializeResponseBody(cached.getResponseBody(), TransferResponse.class);
        }

        Wallet fromWallet = fromId.equals(firstId) ? firstWallet : secondWallet;
        Wallet toWallet = toId.equals(firstId) ? firstWallet : secondWallet;

        // Step 3: Authorization Check (if authenticated caller is present, verify source wallet ownership)
        String callerUserId = SecurityUserContext.getCurrentUser();
        if (callerUserId != null && !callerUserId.isBlank()) {
            if (!fromWallet.getUserId().equals(callerUserId)) {
                log.warn("Unauthorized transfer attempt: caller {} is not owner of wallet {}", callerUserId, fromId);
                throw new UnauthorizedException(String.format("User '%s' is not authorized to transfer from wallet '%s'",
                        callerUserId, fromId));
            }
        } else {
            callerUserId = fromWallet.getUserId();
        }

        // Step 4: No Overdraft Verification
        if (!fromWallet.canDebit(amountPaise)) {
            MDC.put("wallet_id", fromId);
            MDC.put("event_type", "transfer_declined_insufficient_funds");
            log.warn("Transfer declined: wallet {} balance {} paise < required {} paise",
                    fromId, fromWallet.getBalancePaise(), amountPaise);
            MDC.remove("event_type");

            metricsService.incrementTransfersDeclined();

            java.util.Map<String, Object> declineData = new java.util.HashMap<>();
            declineData.put("wallet_id", fromId);
            declineData.put("current_balance_paise", fromWallet.getBalancePaise());
            declineData.put("requested_amount_paise", amountPaise);
            logEventService.recordDomainEvent("transfer_declined_insufficient_funds", declineData);

            String transferId = "trf_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            Transfer declinedTransfer = new Transfer(
                    transferId, fromId, toId, amountPaise, idempotencyKey,
                    TransferStatus.DECLINED_INSUFFICIENT_FUNDS,
                    "Insufficient balance"
            );
            transferRepository.save(declinedTransfer);

            // Record declined outcome in idempotency store so retries return identical failure
            TransferResponse errorResponse = TransferResponse.fromEntity(declinedTransfer);
            idempotencyService.saveRecord(idempotencyKey, callerUserId, requestHash, 422, errorResponse, transferId);

            throw new InsufficientFundsException(fromId, fromWallet.getBalancePaise(), amountPaise);
        }

        // Step 5: Atomic State Mutation (Conservation invariant guaranteed)
        fromWallet.debit(amountPaise);
        toWallet.credit(amountPaise);

        walletRepository.save(fromWallet);
        walletRepository.save(toWallet);

        String transferId = "trf_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Transfer transfer = new Transfer(
                transferId, fromId, toId, amountPaise, idempotencyKey,
                TransferStatus.COMPLETED, null
        );
        Transfer savedTransfer = transferRepository.save(transfer);

        // Step 6: Log Domain Events
        MDC.put("transfer_id", transferId);
        MDC.put("event_type", "transfer_created");
        log.info("Transfer {} created: {} -> {}, amount: {} paise", transferId, fromId, toId, amountPaise);

        MDC.put("wallet_id", fromId);
        MDC.put("event_type", "debited");
        log.info("Debited {} paise from wallet {}, new balance: {} paise", amountPaise, fromId, fromWallet.getBalancePaise());

        MDC.put("wallet_id", toId);
        MDC.put("event_type", "credited");
        log.info("Credited {} paise to wallet {}, new balance: {} paise", amountPaise, toId, toWallet.getBalancePaise());

        MDC.remove("event_type");
        MDC.remove("wallet_id");
        MDC.remove("transfer_id");

        java.util.Map<String, Object> transferData = new java.util.HashMap<>();
        transferData.put("transfer_id", transferId);
        transferData.put("from_wallet_id", fromId);
        transferData.put("to_wallet_id", toId);
        transferData.put("amount_paise", amountPaise);
        transferData.put("from_balance_paise", fromWallet.getBalancePaise());
        transferData.put("to_balance_paise", toWallet.getBalancePaise());
        logEventService.recordDomainEvent("transfer_created", transferData);

        TransferResponse response = TransferResponse.fromEntity(savedTransfer);

        // Step 7: Atomic Idempotency Record Persistence
        idempotencyService.saveRecord(idempotencyKey, callerUserId, requestHash, 200, response, transferId);

        // Telemetry
        metricsService.incrementTransfersCreated();
        metricsService.recordTransferDuration(System.nanoTime() - startTime);

        return response;
    }

    @Transactional(readOnly = true)
    public TransferResponse getTransfer(String transferId) {
        return transferRepository.findById(transferId)
                .map(TransferResponse::fromEntity)
                .orElseThrow(() -> new TransferNotFoundException(transferId));
    }

    /**
     * Reversal / Refund Transfer: Moves exact amount back from recipient to sender.
     * Evaluates conservation, exactly-once idempotency, and no-overdraft on reversal.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
    public TransferResponse reverseTransfer(String originalTransferId, String reversalIdempotencyKey) {
        Transfer original = transferRepository.findById(originalTransferId)
                .orElseThrow(() -> new TransferNotFoundException(originalTransferId));

        if (original.getStatus() != TransferStatus.COMPLETED) {
            throw new IllegalStateException("Only COMPLETED transfers can be reversed. Current status: " + original.getStatus());
        }

        // Check if this transfer was already reversed
        String reversalKey = reversalIdempotencyKey != null && !reversalIdempotencyKey.isBlank()
                ? reversalIdempotencyKey
                : "rev_" + originalTransferId;

        CreateTransferRequest reversalRequest = new CreateTransferRequest(
                original.getToWalletId(),       // Original recipient is now debited
                original.getFromWalletId(),     // Original sender is now refunded
                original.getAmountPaise(),      // Exact original amount
                reversalKey
        );

        log.info("Executing reversal of transfer {} via idempotency key {}", originalTransferId, reversalKey);
        return executeTransfer(reversalRequest);
    }
}
