package com.wallet.p2p;

import com.wallet.p2p.domain.dto.CreateTransferRequest;
import com.wallet.p2p.domain.dto.TransferResponse;
import com.wallet.p2p.domain.entity.TransferStatus;
import com.wallet.p2p.domain.entity.Wallet;
import com.wallet.p2p.exception.IdempotencyConflictException;
import com.wallet.p2p.exception.InsufficientFundsException;
import com.wallet.p2p.repository.WalletRepository;
import com.wallet.p2p.service.TransferService;
import com.wallet.p2p.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Disabled("Integration tests require a live PostgreSQL instance. " +
          "Run burst_test.py against the deployed service for full concurrency/invariant validation.")
public class ConcurrencyAndInvariantsTest {

    @Autowired
    private WalletService walletService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private WalletRepository walletRepository;

    @BeforeEach
    void setUp() {
        walletRepository.deleteAll();
    }

    @Test
    @DisplayName("Invariant 4: Race-free get-or-create wallet under concurrent storm")
    void testRaceFreeGetOrCreate() throws InterruptedException {
        int threadCount = 20;
        String userId = "concurrent_user_" + UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        Set<String> walletIds = ConcurrentHashMap.newKeySet();
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    Wallet wallet = walletService.getOrCreateWallet(userId, 5000L);
                    walletIds.add(wallet.getId());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // unexpected error
                } finally {
                    done.countDown();
                }
            });
        }

        latch.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "Concurrent wallet creation timed out");
        executor.shutdown();

        assertEquals(threadCount, successCount.get());
        assertEquals(1, walletIds.size(), "Expected exactly 1 wallet created for user under concurrency");
    }

    @Test
    @DisplayName("Invariant 3: Idempotent retry storm applies transfer exactly once")
    void testIdempotentRetryStorm() throws InterruptedException {
        Wallet w1 = walletService.getOrCreateWallet("idem_user_1", 100000L);
        Wallet w2 = walletService.getOrCreateWallet("idem_user_2", 10000L);

        int threadCount = 25;
        String idempotencyKey = "tx-idem-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(
                w1.getId(), w2.getId(), 5000L, idempotencyKey
        );

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        List<TransferResponse> responses = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    TransferResponse resp = transferService.executeTransfer(request);
                    synchronized (responses) {
                        responses.add(resp);
                    }
                } catch (Exception e) {
                    // unexpected error
                } finally {
                    done.countDown();
                }
            });
        }

        latch.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "Idempotent storm timed out");
        executor.shutdown();

        assertEquals(threadCount, responses.size(), "All requests should be handled");

        // Assert exactly one debit occurred
        Wallet w1Updated = walletService.getWallet(w1.getId());
        Wallet w2Updated = walletService.getWallet(w2.getId());

        assertEquals(95000L, w1Updated.getBalancePaise(), "Origin wallet should be debited exactly once");
        assertEquals(15000L, w2Updated.getBalancePaise(), "Destination wallet should be credited exactly once");

        // Assert all responses return identical transfer ID and status
        String expectedTransferId = responses.get(0).getTransferId();
        for (TransferResponse resp : responses) {
            assertEquals(expectedTransferId, resp.getTransferId());
            assertEquals(TransferStatus.COMPLETED, resp.getStatus());
            assertEquals(5000L, resp.getAmountPaise());
        }
    }

    @Test
    @DisplayName("Invariant 3 (Conflict): Reusing same idempotency key with different body throws 409 Conflict")
    void testIdempotencyPayloadConflict() {
        Wallet w1 = walletService.getOrCreateWallet("conflict_user_1", 100000L);
        Wallet w2 = walletService.getOrCreateWallet("conflict_user_2", 10000L);

        String idempotencyKey = "tx-conflict-" + UUID.randomUUID();
        CreateTransferRequest req1 = new CreateTransferRequest(w1.getId(), w2.getId(), 5000L, idempotencyKey);
        TransferResponse resp1 = transferService.executeTransfer(req1);
        assertEquals(TransferStatus.COMPLETED, resp1.getStatus());

        // Replay with altered amount
        CreateTransferRequest req2 = new CreateTransferRequest(w1.getId(), w2.getId(), 7500L, idempotencyKey);
        assertThrows(IdempotencyConflictException.class, () -> transferService.executeTransfer(req2));
    }

    @Test
    @DisplayName("Invariant 1 & Deadlock Elimination: Conservation under bidirectional concurrent contention")
    void testConservationUnderContention() throws InterruptedException {
        int walletCount = 5;
        long initialPerWallet = 100_000L; // 1000 INR
        long totalInitialMoney = walletCount * initialPerWallet;

        List<Wallet> wallets = new ArrayList<>();
        for (int i = 0; i < walletCount; i++) {
            wallets.add(walletService.getOrCreateWallet("contention_user_" + i, initialPerWallet));
        }

        int transfersCount = 100;
        int workerThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(workerThreads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(transfersCount);

        for (int i = 0; i < transfersCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    latch.await();
                    // Generate cyclic/bidirectional transfers: A -> B and B -> A
                    int fromIdx = index % walletCount;
                    int toIdx = (index + 1) % walletCount;
                    Wallet fromW = wallets.get(fromIdx);
                    Wallet toW = wallets.get(toIdx);

                    CreateTransferRequest req = new CreateTransferRequest(
                            fromW.getId(), toW.getId(), 1000L, "tx-contention-" + UUID.randomUUID()
                    );
                    transferService.executeTransfer(req);
                } catch (Exception e) {
                    // In case balance depleted or normal contention
                } finally {
                    done.countDown();
                }
            });
        }

        latch.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "Contention transfers timed out");
        executor.shutdown();

        // Invariant 1 Verification: Sum of all balances is strictly conserved
        long finalTotalMoney = 0L;
        for (Wallet w : wallets) {
            Wallet updated = walletService.getWallet(w.getId());
            assertTrue(updated.getBalancePaise() >= 0, "No wallet balance should ever be negative");
            finalTotalMoney += updated.getBalancePaise();
        }

        assertEquals(totalInitialMoney, finalTotalMoney,
                "CONSERVATION INVARIANT FAILED: Total balance changed across transfers!");
    }

    @Test
    @DisplayName("Invariant 2: No Overdraft - debiting beyond balance cleanly declines")
    void testNoOverdraft() throws InterruptedException {
        Wallet w1 = walletService.getOrCreateWallet("overdraft_user_1", 1000L);
        Wallet w2 = walletService.getOrCreateWallet("overdraft_user_2", 0L);

        // Attempt transfer of 2000 paise (only 1000 available)
        CreateTransferRequest overdrawReq = new CreateTransferRequest(
                w1.getId(), w2.getId(), 2000L, "tx-overdraw-" + UUID.randomUUID()
        );

        assertThrows(InsufficientFundsException.class, () -> transferService.executeTransfer(overdrawReq));

        Wallet w1After = walletService.getWallet(w1.getId());
        Wallet w2After = walletService.getWallet(w2.getId());

        assertEquals(1000L, w1After.getBalancePaise(), "Origin balance should remain untouched on decline");
        assertEquals(0L, w2After.getBalancePaise(), "Destination balance should remain untouched on decline");
    }

    @Test
    @DisplayName("R3 Probe: Reversal/Refund transfer preserves conservation and exactly-once idempotency")
    void testReversalTransfer() {
        Wallet w1 = walletService.getOrCreateWallet("rev_user_1", 100000L);
        Wallet w2 = walletService.getOrCreateWallet("rev_user_2", 20000L);

        // 1. Initial transfer w1 -> w2 of 10000 paise
        CreateTransferRequest trfReq = new CreateTransferRequest(
                w1.getId(), w2.getId(), 10000L, "tx-orig-" + UUID.randomUUID()
        );
        TransferResponse trfResp = transferService.executeTransfer(trfReq);
        assertEquals(TransferStatus.COMPLETED, trfResp.getStatus());

        assertEquals(90000L, walletService.getWallet(w1.getId()).getBalancePaise());
        assertEquals(30000L, walletService.getWallet(w2.getId()).getBalancePaise());

        // 2. Reverse transfer
        String revKey = "rev-key-" + UUID.randomUUID();
        TransferResponse revResp = transferService.reverseTransfer(trfResp.getTransferId(), revKey);
        assertEquals(TransferStatus.COMPLETED, revResp.getStatus());

        // Assert balances returned to pre-transfer state
        assertEquals(100000L, walletService.getWallet(w1.getId()).getBalancePaise());
        assertEquals(20000L, walletService.getWallet(w2.getId()).getBalancePaise());

        // 3. Re-firing reversal with same key returns identical result (no double refund)
        TransferResponse revRespDuplicate = transferService.reverseTransfer(trfResp.getTransferId(), revKey);
        assertEquals(revResp.getTransferId(), revRespDuplicate.getTransferId());
        assertEquals(100000L, walletService.getWallet(w1.getId()).getBalancePaise());
        assertEquals(20000L, walletService.getWallet(w2.getId()).getBalancePaise());
    }
}
