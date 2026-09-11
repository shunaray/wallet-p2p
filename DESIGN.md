# Wallet & P2P Transfer Microservice: Architecture & Technical Design

## 1. Concurrency Control: Conservation & Zero-Overdraft

### Mechanism: Deterministic Sorted Row-Locking (`SELECT ... FOR UPDATE`) + Engine Check Constraints
For executing transfers between two wallets (`from_wallet_id` and `to_wallet_id`), the service acquires **Pessimistic Write Locks (`SELECT ... FOR UPDATE`) in strict lexicographical order** of the wallet primary keys, combined with an in-memory balance verification and a PostgreSQL engine-level check constraint:

$$\text{firstLockId} = \min(\text{fromId}, \text{toId}), \quad \text{secondLockId} = \max(\text{fromId}, \text{toId})$$

```java
Wallet firstWallet = walletRepository.findByIdForUpdate(firstId);
Wallet secondWallet = walletRepository.findByIdForUpdate(secondId);

if (fromWallet.getBalancePaise() < amountPaise) {
    throw new InsufficientFundsException(...);
}

fromWallet.debit(amountPaise);
toWallet.credit(amountPaise);
```

Database schema safety guardrail:
```sql
CONSTRAINT chk_wallet_balance_non_negative CHECK (balance_paise >= 0)
```

### Why This Is the Simplest-Correct Mechanism
1. **Zero Phantom Anomalies & Lost Updates**: Acquiring pessimistic row locks guarantees exclusive mutation rights during the transaction lifespan (`READ COMMITTED` isolation). Neither wallet balance can be read or modified concurrently by another transfer worker.
2. **Deterministic Deadlock Elimination**:
   - The classic deadlock scenario in P2P transfers occurs when Transaction 1 moves money from Wallet $A \to B$ (locking $A$, waiting for $B$) while Transaction 2 concurrently moves money from Wallet $B \to A$ (locking $B$, waiting for $A$).
   - By sorting the wallet IDs lexicographically ($\min(A, B)$ then $\max(A, B)$), both transactions acquire locks in identical physical sequence ($A$ followed by $B$).
   - A circular dependency in the database wait-for graph becomes **mathematically impossible**. Contending transactions queue linearly without deadlocks or unexpected rollback aborts.
3. **Database-Level Defense-in-Depth**:
   - The PostgreSQL `CHECK (balance_paise >= 0)` constraint guarantees that even if an unforeseen application bug bypassed balance checks, the database storage engine rejects any debit that would produce a negative balance.

### Alternatives Evaluated and Trade-Offs
- **Serializable Isolation (`ISOLATION_SERIALIZABLE`)**:
  - Under high concurrent contention (e.g. transfers between a small pool of wallets), PostgreSQL's SSI (Serializable Snapshot Isolation) detects read-write lock dependencies and aborts transactions with `40001 serialization_failure`. This forces application-layer exponential backoff retry loops, introduces tail latency spikes (p99 degradation), and wastes CPU/connection pool resources on repeated rollbacks.
  - *Sorted pessimistic locks*, by contrast, queue deterministically at the row level with zero transaction aborts and minimal latency overhead.
- **Single-Statement Conditional Updates (`UPDATE wallets SET balance = balance - :amt WHERE id = :id AND balance >= :amt`)**:
  - While single conditional updates work well for single-account operations, a P2P transfer is a **two-party transaction** ($A \to B$). If $A$ is debited but $B$'s credit or the audit ledger write fails, transaction rollback is required anyway. Acquiring row locks in sorted order within a declarative `@Transactional` boundary provides clean atomicity, deterministic semantics, and transparent audit logging.

---

## 2. Transactional Idempotency

### Placement & Transaction Boundary
Idempotency is enforced in the **`idempotency_records` table** within the **exact same ACID database transaction** as the wallet balance mutations and transfer ledger entry.

```
┌────────────────────────────────────────────────────────┐
│             SINGLE ACID TRANSACTION                    │
│                                                        │
│  1. Check idempotency_records WHERE key = :key         │
│     - Found & Hash matches -> Return Cached Response   │
│     - Found & Hash differs -> Throw 409 Conflict       │
│                                                        │
│  2. Acquire Pessimistic Locks (min(A, B), max(A, B))   │
│  3. Post-Lock Idempotency Double-Check                 │
│  4. Debit fromWallet, Credit toWallet                  │
│  5. Insert transfers record (status: COMPLETED)        │
│  6. Insert idempotency_records (key, hash, status=200) │
└────────────────────────────────────────────────────────┘
```

### Request Fingerprinting & Conflict Handling
- An incoming request's idempotency key is accompanied by a SHA-256 fingerprint of the request payload:
  $$\text{request\_hash} = \text{SHA256}(\text{from\_wallet\_id} + ":" + \text{to\_wallet\_id} + ":" + \text{amount\_paise})$$
- When an idempotency key is re-submitted:
  1. **Identical Replay (`key` exists AND `stored_hash == new_hash`)**:
     - The transfer is NOT re-executed.
     - The stored response (status code and JSON body) is retrieved and returned immediately.
     - Domain metric `transfers.idempotent_replays{result="HIT"}` increments, and `idempotent_replay_hit` is logged.
  2. **Conflict / Reused Key with Different Body (`key` exists AND `stored_hash != new_hash`)**:
     - The request is immediately rejected with **HTTP 409 Conflict** (`IDEMPOTENCY_KEY_CONFLICT`).
     - No funds are debited or transferred.

Because idempotency insertion and balance mutation are committed in the same transaction, there is **zero possibility of a "zombie debit"** where money was debited but the idempotency state failed to commit.

---

## 3. Consistency vs. Availability Trade-offs

### Architectural Choice: Strong Consistency (CP over AP)
Under the CAP / PACELC theorem, this financial wallet service chooses **PC/EC (Consistent under Partitions, Consistent under normal operation)**:

1. **Why Consistency Trumps Availability**:
   - In financial ledgers, **conservation of money is an absolute physical invariant**. Double spending, phantom credits, or temporary negative balances cannot be "eventually resolved" or merged asynchronously without severe financial risk.
   - We consciously sacrifice raw availability during node or database network partition events to ensure that no two nodes ever disagree on a wallet's balance.
2. **What Is Consciously Given Up**:
   - **Multi-region active-active writes without consensus**: In exchange for immediate consistency, writes must route through a primary relational store (PostgreSQL) or synchronous multi-AZ replicas. If the primary database is unreachable, write transfers will fail (availability loss) rather than accepting unverified optimistic debits.
   - **Sub-millisecond unbounded throughput on a single account**: By acquiring pessimistic write locks, transfers touching the exact same wallet serialize behind one another. While different wallet pairs execute in parallel with full concurrency, a single "hot" wallet experiences natural queuing delay to preserve the zero-overdraft invariant.

---

## 4. Observability & Telemetry Architecture

### Structured JSON Logging with SLF4J MDC
Every log output is emitted as structured JSON on `STDOUT` via `logstash-logback-encoder`. Every request is stamped with a unique `correlation_id` (via `X-Correlation-Id` or generated UUID) across filters, services, and repositories:
```json
{
  "timestamp": "2026-09-11T13:40:15.123Z",
  "level": "INFO",
  "thread": "http-nio-8080-exec-3",
  "logger": "com.wallet.service.TransferService",
  "message": "Transfer trf_8921 created: wal_1 -> wal_2, amount: 5000 paise",
  "correlation_id": "c7a8b941-8f52-4f12-8e10-9b6f1e8e4a90",
  "user_id": "usr_alpha",
  "wallet_id": "wal_1",
  "transfer_id": "trf_8921",
  "event_type": "transfer_created"
}
```

### Prometheus Metrics & Actuator
Exposed natively at `/actuator/prometheus`:
- `transfers_total{status="COMPLETED"}`: Counter of successful transfers.
- `transfers_declined_total{reason="INSUFFICIENT_FUNDS"}`: Counter of clean overdraft rejections.
- `transfers_idempotent_replays_total{result="HIT"}`: Counter of idempotent replays served.
- `http_server_requests_seconds{quantile="0.99"}`: Automatic p99 HTTP latency histograms.
- HikariCP pool utilization and JVM memory gauges.

### Multi-Stage Containerization
- **Build Stage**: `maven:3.9.6-eclipse-temurin-17-alpine`
- **Runtime Stage**: `eclipse-temurin:17-jre-alpine`
- **Security**: Non-root user `spring` (UID 10001, GID 10001).
- **Healthcheck**: Validates `/actuator/health` probe every 15 seconds.
