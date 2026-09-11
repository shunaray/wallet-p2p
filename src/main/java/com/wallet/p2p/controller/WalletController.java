package com.wallet.p2p.controller;

import com.wallet.p2p.config.SecurityUserContext;
import com.wallet.p2p.domain.dto.CreateWalletRequest;
import com.wallet.p2p.domain.dto.WalletResponse;
import com.wallet.p2p.domain.entity.Wallet;
import com.wallet.p2p.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    /**
     * POST /wallets - Get-or-create a wallet for a user.
     * Identifies user via Bearer token in Authorization header, or fallback to request body user_id.
     */
    @PostMapping
    public ResponseEntity<WalletResponse> getOrCreateWallet(
            @Valid @RequestBody(required = false) CreateWalletRequest request) {

        String userId = SecurityUserContext.getCurrentUser();

        if (request != null && StringUtils.hasText(request.getUserId())) {
            userId = request.getUserId();
        }

        if (!StringUtils.hasText(userId)) {
            // Generate deterministic or ad-hoc user ID if none supplied
            userId = "user_" + UUID.randomUUID().toString().substring(0, 8);
        }

        Long initialBalance = (request != null) ? request.getInitialBalancePaise() : 0L;
        Wallet wallet = walletService.getOrCreateWallet(userId, initialBalance);

        return ResponseEntity.status(HttpStatus.OK).body(WalletResponse.fromEntity(wallet));
    }

    /**
     * GET /wallets/{id} - Get current balance of the specified wallet.
     */
    @GetMapping("/{id}")
    public ResponseEntity<WalletResponse> getWallet(@PathVariable("id") String walletId) {
        Wallet wallet = walletService.getWallet(walletId);
        return ResponseEntity.ok(WalletResponse.fromEntity(wallet));
    }
}
