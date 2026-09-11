#!/usr/bin/env python3
"""
Wallet & P2P Transfer Microservice: Live Invariant & Concurrency Verification Probe
Usage:
    python burst_test.py [BASE_URL]

Examples:
    python burst_test.py http://localhost:8080
    python burst_test.py https://p2p-wallet-service.onrender.com
"""

import sys
import json
import uuid
import time
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
from concurrent.futures import ThreadPoolExecutor, as_completed

# Force UTF-8 on Windows terminal if available
if hasattr(sys.stdout, 'reconfigure'):
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass

BASE_URL = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else "http://localhost:8080"

print(f"\n[*] Starting Live Invariant & Concurrency Probe against: {BASE_URL}\n" + "=" * 60)


def http_request(method, endpoint, payload=None, bearer_token=None, accept="application/json"):
    url = f"{BASE_URL}{endpoint}"
    headers = {
        "Content-Type": "application/json",
        "Accept": accept
    }
    if bearer_token:
        headers["Authorization"] = f"Bearer {bearer_token}"

    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = Request(url, data=data, headers=headers, method=method)

    try:
        with urlopen(req, timeout=10) as resp:
            status = resp.status
            raw_body = resp.read().decode("utf-8")
            if "json" in resp.headers.get("Content-Type", ""):
                body = json.loads(raw_body)
            else:
                body = raw_body
            return status, body
    except HTTPError as e:
        err_body = e.read().decode("utf-8")
        try:
            err_json = json.loads(err_body)
        except Exception:
            err_json = {"error": err_body}
        return e.code, err_json
    except URLError as e:
        return 0, {"error": str(e.reason)}


# ==============================================================================
# PROBE 1: CONCURRENT GET-OR-CREATE
# ==============================================================================
def probe_concurrent_get_or_create(n=25):
    print(f"\n[PROBE 1] Concurrent get-or-create: firing {n} simultaneous POST /wallets for a new user...")
    user_id = f"storm_user_{uuid.uuid4().hex[:8]}"

    def create_wallet_call():
        return http_request("POST", "/wallets", {"user_id": user_id, "initial_balance_paise": 50000}, bearer_token=user_id)

    results = []
    with ThreadPoolExecutor(max_workers=n) as executor:
        futures = [executor.submit(create_wallet_call) for _ in range(n)]
        for f in as_completed(futures):
            results.append(f.result())

    statuses = [r[0] for r in results]
    wallet_ids = set()
    for status, body in results:
        if status in (200, 201) and "wallet_id" in body:
            wallet_ids.add(body["wallet_id"])

    print(f"   -> HTTP statuses: {set(statuses)}")
    print(f"   -> Distinct wallet IDs returned: {len(wallet_ids)} ({list(wallet_ids)})")

    assert len(wallet_ids) == 1, f"[FAIL] Expected exactly 1 wallet for user, got {len(wallet_ids)}"
    assert all(s in (200, 201) for s in statuses), f"[FAIL] Non-success status returned: {statuses}"
    print("   [PASS] Exactly one wallet created under concurrent storm!")
    return list(wallet_ids)[0]


# ==============================================================================
# PROBE 2: IDEMPOTENT RETRY STORM
# ==============================================================================
def probe_idempotent_retry_storm(k=30):
    print(f"\n[PROBE 2] Idempotent retry storm: firing same transfer (same key) {k} times concurrently...")
    user1 = f"idem_usr1_{uuid.uuid4().hex[:6]}"
    user2 = f"idem_usr2_{uuid.uuid4().hex[:6]}"

    _, w1 = http_request("POST", "/wallets", {"user_id": user1, "initial_balance_paise": 100000}, bearer_token=user1)
    _, w2 = http_request("POST", "/wallets", {"user_id": user2, "initial_balance_paise": 20000}, bearer_token=user2)

    w1_id, w2_id = w1["wallet_id"], w2["wallet_id"]
    idempotency_key = f"key_{uuid.uuid4().hex}"
    transfer_payload = {
        "from_wallet_id": w1_id,
        "to_wallet_id": w2_id,
        "amount_paise": 5000,
        "idempotency_key": idempotency_key
    }

    def fire_transfer():
        return http_request("POST", "/transfers", transfer_payload, bearer_token=user1)

    results = []
    with ThreadPoolExecutor(max_workers=k) as executor:
        futures = [executor.submit(fire_transfer) for _ in range(k)]
        for f in as_completed(futures):
            results.append(f.result())

    statuses = [r[0] for r in results]
    transfer_ids = set()
    for status, body in results:
        if "transfer_id" in body:
            transfer_ids.add(body["transfer_id"])

    print(f"   -> HTTP statuses: {set(statuses)}")
    print(f"   -> Distinct transfer IDs returned: {len(transfer_ids)} ({list(transfer_ids)})")

    assert len(transfer_ids) == 1, f"[FAIL] Expected exactly 1 transfer execution, got {len(transfer_ids)}"
    assert all(s == 200 for s in statuses), f"[FAIL] Non-200 status: {statuses}"

    # Verify balance changed exactly once
    _, w1_after = http_request("GET", f"/wallets/{w1_id}", bearer_token=user1)
    _, w2_after = http_request("GET", f"/wallets/{w2_id}", bearer_token=user2)

    print(f"   -> Wallet 1 balance: {w1_after['balance_paise']} paise (expected 95000)")
    print(f"   -> Wallet 2 balance: {w2_after['balance_paise']} paise (expected 25000)")
    assert w1_after["balance_paise"] == 95000, "[FAIL] w1 was not debited exactly once!"
    assert w2_after["balance_paise"] == 25000, "[FAIL] w2 was not credited exactly once!"

    # Probe 2b: Reused key with DIFFERENT body must yield 409 Conflict
    conflict_payload = {
        "from_wallet_id": w1_id,
        "to_wallet_id": w2_id,
        "amount_paise": 9999,  # different amount!
        "idempotency_key": idempotency_key
    }
    c_status, c_body = http_request("POST", "/transfers", conflict_payload, bearer_token=user1)
    print(f"   -> Reused key with altered body returned status: {c_status}")
    assert c_status == 409, f"[FAIL] Expected 409 Conflict on payload mismatch, got {c_status}"

    print("   [PASS] Idempotent replay storm and 409 conflict verified!")


# ==============================================================================
# PROBE 3: CONSERVATION UNDER CONTENTION
# ==============================================================================
def probe_conservation_under_contention(num_wallets=5, num_transfers=60):
    print(f"\n[PROBE 3] Conservation under contention: {num_transfers} transfers across {num_wallets} wallets...")
    initial_each = 100000  # 1000 INR
    initial_total = num_wallets * initial_each

    wallets = []
    users = []
    for i in range(num_wallets):
        u = f"contention_u{i}_{uuid.uuid4().hex[:6]}"
        users.append(u)
        _, w = http_request("POST", "/wallets", {"user_id": u, "initial_balance_paise": initial_each}, bearer_token=u)
        wallets.append(w["wallet_id"])

    print(f"   -> Created {num_wallets} wallets with total pool: {initial_total} paise (Rs. {initial_total / 100:.2f})")

    def perform_transfer(idx):
        from_idx = idx % num_wallets
        to_idx = (idx + 1) % num_wallets
        payload = {
            "from_wallet_id": wallets[from_idx],
            "to_wallet_id": wallets[to_idx],
            "amount_paise": 1500,
            "idempotency_key": f"tx_contention_{idx}_{uuid.uuid4().hex}"
        }
        return http_request("POST", "/transfers", payload, bearer_token=users[from_idx])

    with ThreadPoolExecutor(max_workers=12) as executor:
        futures = [executor.submit(perform_transfer, i) for i in range(num_transfers)]
        for f in as_completed(futures):
            f.result()

    # Sum final balances
    final_total = 0
    final_balances = []
    for i, wid in enumerate(wallets):
        _, w = http_request("GET", f"/wallets/{wid}", bearer_token=users[i])
        bal = w["balance_paise"]
        final_balances.append(bal)
        final_total += bal
        assert bal >= 0, f"[FAIL] Negative balance detected in wallet {wid}: {bal}"

    print(f"   -> Final wallet balances: {final_balances}")
    print(f"   -> Sum of all balances: {final_total} paise (Initial: {initial_total} paise)")

    assert final_total == initial_total, f"[FAIL] CONSERVATION INVARIANT BROKEN: {final_total} != {initial_total}"
    print("   [PASS] Total balance is strictly conserved! Zero overdrafts and zero deadlocks.")


# ==============================================================================
# PROBE 4: R3 LIVE EXTENSION - REVERSAL / REFUND TRANSFER
# ==============================================================================
def probe_reversal():
    print(f"\n[PROBE 4] Reversal / Refund: testing POST /transfers/{{id}}/reverse...")
    u1, u2 = f"u_rev1_{uuid.uuid4().hex[:6]}", f"u_rev2_{uuid.uuid4().hex[:6]}"
    _, w1 = http_request("POST", "/wallets", {"user_id": u1, "initial_balance_paise": 10000}, bearer_token=u1)
    _, w2 = http_request("POST", "/wallets", {"user_id": u2, "initial_balance_paise": 5000}, bearer_token=u2)

    w1_id, w2_id = w1["wallet_id"], w2["wallet_id"]

    # 1. Execute initial transfer: w1 -> w2 of 3000 paise
    trf_key = f"tx_orig_{uuid.uuid4().hex}"
    _, trf = http_request("POST", "/transfers", {
        "from_wallet_id": w1_id,
        "to_wallet_id": w2_id,
        "amount_paise": 3000,
        "idempotency_key": trf_key
    }, bearer_token=u1)

    assert trf.get("status") == "COMPLETED", f"[FAIL] Transfer failed: {trf}"
    print(f"   -> Initial transfer {trf['transfer_id']} completed: 3000 paise w1 -> w2")

    # 2. Reverse transfer
    rev_key = f"rev_key_{uuid.uuid4().hex}"
    rev_status, rev = http_request("POST", f"/transfers/{trf['transfer_id']}/reverse", {
        "idempotency_key": rev_key
    }, bearer_token=u2)

    assert rev_status == 200, f"[FAIL] Reversal returned status {rev_status}: {rev}"
    assert rev.get("status") == "COMPLETED", f"[FAIL] Reversal status: {rev}"
    print(f"   -> Reversal {rev['transfer_id']} executed successfully")

    # 3. Check balances returned to exact initial amounts
    _, w1_final = http_request("GET", f"/wallets/{w1_id}", bearer_token=u1)
    _, w2_final = http_request("GET", f"/wallets/{w2_id}", bearer_token=u2)

    assert w1_final["balance_paise"] == 10000, f"[FAIL] w1 balance not restored: {w1_final['balance_paise']}"
    assert w2_final["balance_paise"] == 5000, f"[FAIL] w2 balance not restored: {w2_final['balance_paise']}"
    print(f"   -> Balances restored: w1={w1_final['balance_paise']}, w2={w2_final['balance_paise']}")

    # 4. Idempotent retry of reversal must not double-refund
    _, rev_dup = http_request("POST", f"/transfers/{trf['transfer_id']}/reverse", {
        "idempotency_key": rev_key
    }, bearer_token=u2)
    assert rev_dup.get("transfer_id") == rev.get("transfer_id"), "[FAIL] Reversal duplicate gave different transfer ID"

    _, w1_dup = http_request("GET", f"/wallets/{w1_id}", bearer_token=u1)
    assert w1_dup["balance_paise"] == 10000, "[FAIL] Double refund occurred!"
    print("   [PASS] Reversal transfer, balance restoration, and reversal idempotency verified!")


# ==============================================================================
# TELEMETRY CHECK
# ==============================================================================
def probe_telemetry():
    print(f"\n[PROBE 5] Telemetry check: querying /actuator/health, /actuator/prometheus, and /logs...")
    h_status, health = http_request("GET", "/actuator/health")
    print(f"   -> /actuator/health: status {h_status}, body: {health.get('status') if isinstance(health, dict) else health}")

    p_status, prom = http_request("GET", "/actuator/prometheus", accept="text/plain")
    prom_lines = len(prom.splitlines()) if isinstance(prom, str) else 0
    print(f"   -> /actuator/prometheus: status {p_status}, metrics count: {prom_lines} lines")

    l_status, logs = http_request("GET", "/logs")
    print(f"   -> /logs (public structured logs): status {l_status}, recent log entries: {len(logs) if isinstance(logs, list) else 0}")
    assert h_status == 200, f"[FAIL] Health check status: {h_status}"
    assert p_status == 200, f"[FAIL] Prometheus status: {p_status}"
    assert l_status == 200, f"[FAIL] Logs status: {l_status}"
    print("   [PASS] Observability endpoints online and reporting metrics.")


if __name__ == "__main__":
    try:
        probe_concurrent_get_or_create()
        probe_idempotent_retry_storm()
        probe_conservation_under_contention()
        probe_reversal()
        probe_telemetry()
        print("\n" + "=" * 60 + "\n[ALL INVARIANTS & CONCURRENCY PROBES PASSED PERFECTLY]\n" + "=" * 60)
    except Exception as ex:
        print(f"\n[FAIL] PROBE EXECUTION ERROR: {ex}")
        sys.exit(1)
