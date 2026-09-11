package com.wallet.p2p.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.p2p.domain.dto.CreateTransferRequest;
import com.wallet.p2p.domain.entity.IdempotencyRecord;
import com.wallet.p2p.exception.IdempotencyConflictException;
import com.wallet.p2p.repository.IdempotencyRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRecordRepository idempotencyRecordRepository, ObjectMapper objectMapper) {
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.objectMapper = objectMapper;
    }

    public String computeRequestHash(CreateTransferRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String payload = String.format("%s:%s:%d",
                    request.getFromWalletId(),
                    request.getToWalletId(),
                    request.getAmountPaise());
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    public Optional<IdempotencyRecord> checkIdempotency(String idempotencyKey, String expectedHash) {
        Optional<IdempotencyRecord> recordOpt = idempotencyRecordRepository.findById(idempotencyKey);
        if (recordOpt.isPresent()) {
            IdempotencyRecord record = recordOpt.get();
            if (!record.getRequestHash().equals(expectedHash)) {
                log.warn("Idempotency conflict: key {} reused with different payload", idempotencyKey);
                throw new IdempotencyConflictException(idempotencyKey);
            }
        }
        return recordOpt;
    }

    public void saveRecord(String idempotencyKey, String userId, String requestHash,
                           int responseStatus, Object responseBody, String transferId) {
        try {
            String serializedBody = objectMapper.writeValueAsString(responseBody);
            IdempotencyRecord record = new IdempotencyRecord(
                    idempotencyKey,
                    userId,
                    requestHash,
                    responseStatus,
                    serializedBody,
                    transferId
            );
            idempotencyRecordRepository.save(record);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize idempotency response body", e);
            throw new IllegalStateException("Serialization failure", e);
        }
    }

    public <T> T deserializeResponseBody(String body, Class<T> clazz) {
        try {
            return objectMapper.readValue(body, clazz);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize cached idempotency response", e);
        }
    }
}
