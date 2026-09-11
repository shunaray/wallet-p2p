package com.wallet.p2p.repository;

import com.wallet.p2p.domain.entity.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TransferRepository extends JpaRepository<Transfer, String> {

    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);

    List<Transfer> findByFromWalletIdOrToWalletIdOrderByCreatedAtDesc(String fromWalletId, String toWalletId);
}
