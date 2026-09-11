package com.wallet.p2p.repository;

import com.wallet.p2p.domain.entity.Wallet;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, String> {

    Optional<Wallet> findByUserId(String userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") String id);

    @Modifying
    @Query(value = "INSERT INTO wallets (id, user_id, balance_paise, created_at, updated_at) " +
                   "VALUES (:id, :userId, :balancePaise, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) " +
                   "ON CONFLICT (user_id) DO NOTHING", nativeQuery = true)
    int insertIfNotExists(@Param("id") String id,
                          @Param("userId") String userId,
                          @Param("balancePaise") long balancePaise);
}
