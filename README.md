# Wallet & P2P Transfer Microservice

Enterprise-grade, concurrency-resilient Wallet & P2P Transfer microservice built with **Spring Boot 3.3.3 (Java 17/21)**, **PostgreSQL 16**, **Flyway**, **Micrometer/Prometheus**, and **Structured JSON Logging**.

---

## 🏛 Core Invariants & Architecture

1. **Conservation**: The sum of all wallet balances is strictly conserved across all transfers ($\sum \text{balance}_{\text{final}} = \sum \text{balance}_{\text{initial}}$). No money is created or destroyed.
2. **No Overdraft**: A wallet balance never goes negative. Evaluated in-memory and guarded by a database engine check constraint `CHECK (balance_paise >= 0)`. Insufficient balance transfers fail cleanly with HTTP 422.
3. **Exactly-Once Transfer Idempotency**:
   - Re-sending the same `idempotency_key` with identical parameters returns the original cached result (`idempotent_replay_hit`).
   - Re-sending the same `idempotency_key` with a different payload returns **HTTP 409 Conflict**.
   - Transactionally atomic: Idempotency state and balances are committed in the same database transaction.
4. **Race-Free Get-or-Create**: Concurrent `POST /wallets` requests for the same user yield exactly one wallet.
5. **Deadlock Elimination**: Row locks are acquired in strictly sorted lexicographical order (`min(from, to)` followed by `max(from, to)`), eliminating AB-BA deadlocks under heavy concurrent circular transfers.

---

## 🚀 Quick Start with Docker Compose

Bring up the complete stack (Spring Boot app + PostgreSQL 16) with one command:

```bash
docker compose up --build -d
```

Check status and health:
```bash
docker compose ps
```

The service is available at `http://localhost:8080`.

---

## 🧪 Automated Burst & Live Probe Testing

A dedicated multi-threaded probe script reproduces all live evaluation scenarios:

```bash
# Run against local instance
python burst_test.py http://localhost:8080

# Or run against your deployed public cloud URL
python burst_test.py https://p2p-wallet-service.onrender.com
```

### What `burst_test.py` Verifies:
- **Probe 1: Concurrent Get-or-Create**: Fires 25 simultaneous `POST /wallets` calls for a brand-new user; validates that exactly 1 wallet is created.
- **Probe 2: Idempotent Retry Storm**: Fires 30 concurrent transfer requests with identical keys; verifies that exactly 1 debit/credit occurs and all 30 callers receive identical responses. Tests that an altered body yields `409 Conflict`.
- **Probe 3: Conservation Under Contention**: Executes 60 rapid concurrent circular transfers ($A \to B, B \to A$) across 5 wallets; verifies total money pool is unchanged to the single paise, no balance is negative, and zero deadlocks occurred.
- **Probe 4: Telemetry**: Queries `/actuator/health`, `/actuator/prometheus`, and `/logs`.

---

## 📡 API Reference

### 1. Get-or-Create Wallet
```http
POST /wallets
Authorization: Bearer <user_id>
Content-Type: application/json

{
  "user_id": "usr_101",
  "initial_balance_paise": 100000
}
```

**Response (`200 OK`)**:
```json
{
  "wallet_id": "wal_8f2190bbd4a243d1",
  "user_id": "usr_101",
  "balance_paise": 100000,
  "created_at": "2026-09-11T13:45:00Z",
  "updated_at": "2026-09-11T13:45:00Z"
}
```

### 2. Get Wallet Balance
```http
GET /wallets/{wallet_id}
Authorization: Bearer <user_id>
```

**Response (`200 OK`)**:
```json
{
  "wallet_id": "wal_8f2190bbd4a243d1",
  "user_id": "usr_101",
  "balance_paise": 100000,
  "updated_at": "2026-09-11T13:45:00Z"
}
```

### 3. Execute Transfer
```http
POST /transfers
Authorization: Bearer <user_id>
Content-Type: application/json

{
  "from_wallet_id": "wal_8f2190bbd4a243d1",
  "to_wallet_id": "wal_99a80b1c2d3e4f50",
  "amount_paise": 5000,
  "idempotency_key": "client-uuid-12345"
}
```

**Success Response (`200 OK`)**:
```json
{
  "transfer_id": "trf_b6781290fe34ab51",
  "from_wallet_id": "wal_8f2190bbd4a243d1",
  "to_wallet_id": "wal_99a80b1c2d3e4f50",
  "amount_paise": 5000,
  "idempotency_key": "client-uuid-12345",
  "status": "COMPLETED",
  "error_reason": null,
  "created_at": "2026-09-11T13:45:10Z"
}
```

**Insufficient Funds (`422 Unprocessable Entity - RFC 7807`)**:
```json
{
  "type": "https://api.wallet.com/errors/insufficient-funds",
  "title": "Insufficient Funds",
  "status": 422,
  "detail": "Wallet 'wal_8f2190bbd4a243d1' has insufficient balance (1000 paise) for transfer of 5000 paise",
  "error_code": "INSUFFICIENT_FUNDS",
  "current_balance_paise": 1000,
  "requested_amount_paise": 5000,
  "timestamp": "2026-09-11T13:45:12Z"
}
```

**Idempotency Conflict (`409 Conflict - RFC 7807`)**:
```json
{
  "type": "https://api.wallet.com/errors/idempotency-conflict",
  "title": "Idempotency Conflict",
  "status": 409,
  "detail": "Idempotency key 'client-uuid-12345' has already been used with a different request payload",
  "error_code": "IDEMPOTENCY_KEY_CONFLICT",
  "timestamp": "2026-09-11T13:45:15Z"
}
```

### 4. Get Transfer Status
```http
GET /transfers/{transfer_id}
```

---

## 📊 Observability & Telemetry

- **Prometheus Metrics**: `GET /actuator/prometheus`
  - `transfers_total{status="COMPLETED"}`
  - `transfers_declined_total{reason="INSUFFICIENT_FUNDS"}`
  - `transfers_idempotent_replays_total{result="HIT"}`
  - `http_server_requests_seconds` (p95/p99 histograms)
- **Health / Liveness / Readiness**: `GET /actuator/health`
- **Live Structured Domain Logs**: `GET /logs`
  - Returns recent structured JSON domain events (`transfer_created`, `debited`, `credited`, `transfer_declined_insufficient_funds`, `idempotent_replay_hit`).

---