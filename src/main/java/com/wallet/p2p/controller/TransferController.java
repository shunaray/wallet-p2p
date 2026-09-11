package com.wallet.p2p.controller;

import com.wallet.p2p.domain.dto.CreateTransferRequest;
import com.wallet.p2p.domain.dto.TransferResponse;
import com.wallet.p2p.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * POST /transfers - Move money from one wallet to another.
     * Guaranteed conservation, no-overdraft, and exactly-once idempotency.
     */
    @PostMapping
    public ResponseEntity<TransferResponse> createTransfer(@Valid @RequestBody CreateTransferRequest request) {
        TransferResponse response = transferService.executeTransfer(request);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    /**
     * GET /transfers/{id} - Get transfer status and details.
     */
    @GetMapping("/{id}")
    public ResponseEntity<TransferResponse> getTransfer(@PathVariable("id") String transferId) {
        TransferResponse response = transferService.getTransfer(transferId);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /transfers/{id}/reverse - Reversal / refund transfer.
     * Moves exact amount back from original recipient to original sender.
     */
    @PostMapping("/{id}/reverse")
    public ResponseEntity<TransferResponse> reverseTransfer(
            @PathVariable("id") String transferId,
            @RequestBody(required = false) java.util.Map<String, String> body) {
        String idempotencyKey = (body != null) ? body.get("idempotency_key") : null;
        TransferResponse response = transferService.reverseTransfer(transferId, idempotencyKey);
        return ResponseEntity.ok(response);
    }
}
