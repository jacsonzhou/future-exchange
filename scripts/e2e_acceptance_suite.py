#!/usr/bin/env python3
"""
One-click end-to-end acceptance suite for core trading flows.

Covered flows:
1) Login
2) Order cancel path (create passive order -> cancel -> verify CANCELED)
3) Match/fill path (create aggressive order -> verify FILLED)
4) Manual close path (opposite aggressive order -> verify position reduced)
5) Liquidation price formula check
6) PNL push check (reuse scripts/test_position_up_mark_push.py)
7) Optional liquidation trigger execution smoke test

Report outputs:
- markdown summary
- json details
"""

from __future__ import annotations

import argparse
import json
import os
import socket
import ssl
import subprocess
import sys
import time
from dataclasses import dataclass, asdict
from decimal import Decimal, ROUND_HALF_UP, InvalidOperation
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


SCALE = Decimal("100000000")
MAINTENANCE_MARGIN_RATE = Decimal("0.005")
DEFAULT_LEVERAGE = Decimal("10")
PROXY_ENV_KEYS = (
    "http_proxy",
    "https_proxy",
    "all_proxy",
    "HTTP_PROXY",
    "HTTPS_PROXY",
    "ALL_PROXY",
    "no_proxy",
    "NO_PROXY",
)
ACTIVE_ORDER_STATUSES = {"NEW", "PENDING_RISK", "FROZEN", "PARTIALLY_FILLED"}


@dataclass
class StepResult:
    name: str
    ok: bool
    message: str
    details: dict[str, Any]


class SuiteError(RuntimeError):
    pass


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="E2E acceptance suite")
    p.add_argument("--api-gateway", default="http://localhost:8082")
    p.add_argument("--oms-base", default="http://localhost:8081")
    p.add_argument("--match-base", default="http://localhost:8083")
    p.add_argument("--position-internal-base", default="http://localhost:8086")
    p.add_argument("--liquidation-base", default="http://localhost:8102")
    p.add_argument("--private-ws", default="ws://localhost:8097/ws/private")
    p.add_argument("--username", default="zhoufan6")
    p.add_argument("--password", default="123456")
    p.add_argument("--maker-username", default=os.getenv("MAKER_USERNAME", "zhoufan"))
    p.add_argument("--maker-password", default=os.getenv("MAKER_PASSWORD", "123456"))
    p.add_argument("--symbol", default="BTCUSDT")
    p.add_argument("--kafka-container", default=os.getenv("KAFKA_CONTAINER", "kafka-1"))
    p.add_argument("--kafka-broker", default=os.getenv("KAFKA_BROKER", "localhost:9092"))
    p.add_argument("--mark-topic", default=os.getenv("MARK_TOPIC", "mark-price-update"))
    p.add_argument("--liquidation-topic", default=os.getenv("LIQUIDATION_TOPIC", "liquidation-trigger-topic"))
    p.add_argument("--mysql-container", default=os.getenv("MYSQL_CONTAINER", "web3-mysql"))
    p.add_argument("--mysql-user", default=os.getenv("MYSQL_USER", "root"))
    p.add_argument("--mysql-password", default=os.getenv("MYSQL_PASSWORD", "root123456"))
    p.add_argument("--skip-runtime-fix", action="store_true", help="Skip runtime schema fix before checks")
    p.add_argument("--skip-test-state-reset", action="store_true", help="Skip cleaning test account state")
    p.add_argument("--trade-qty-int", type=int, default=100000000)
    p.add_argument("--close-qty-int", type=int, default=50000000)
    p.add_argument("--cancel-qty-int", type=int, default=100000000)
    p.add_argument("--liquidation-qty-int", type=int, default=50000000)
    p.add_argument(
        "--liquidation-liquidity-multiplier",
        type=int,
        default=3,
        help="Counterparty seed multiplier for liquidation full-fill reproducibility",
    )
    p.add_argument(
        "--service-probe-timeout-sec",
        type=int,
        default=10,
        help="Startup probe window for required service ports",
    )
    p.add_argument(
        "--liquidation-probe-timeout-sec",
        type=int,
        default=30,
        help="Probe window for liquidation-core readiness",
    )
    p.add_argument(
        "--liquidation-probe-stable-sec",
        type=int,
        default=3,
        help="Consecutive seconds liquidation-core port must stay open",
    )
    p.add_argument(
        "--liquidation-restart-attempts",
        type=int,
        default=2,
        help="Restart attempts when liquidation-core is not stable",
    )
    p.add_argument("--timeout-sec", type=int, default=45)
    p.add_argument("--auto-start", action="store_true", help="Start required services if missing")
    p.add_argument("--with-liquidation", action="store_true", help="Run liquidation trigger smoke stage")
    p.add_argument("--strict-liquidation", action="store_true", help="Fail suite if liquidation stage cannot run")
    p.add_argument("--skip-pnl-push", action="store_true")
    p.add_argument("--pnl-retries", type=int, default=2, help="Retry count for websocket PNL push verification")
    p.add_argument("--report-dir", default="test-reports")
    return p.parse_args()


def disable_proxy_for_local() -> None:
    # Keep script deterministic in environments with global proxy.
    for key in PROXY_ENV_KEYS:
        os.environ.pop(key, None)
    os.environ["NO_PROXY"] = "localhost,127.0.0.1,::1"
    os.environ["no_proxy"] = "localhost,127.0.0.1,::1"


def run_cmd(
    cmd: list[str],
    timeout: int = 20,
    cwd: str | None = None,
    env: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        cmd,
        cwd=cwd,
        env=env,
        text=True,
        capture_output=True,
        timeout=timeout,
        check=False,
    )


def run_mysql_sql(
    mysql_container: str,
    mysql_user: str,
    mysql_password: str,
    sql: str,
    timeout: int = 20,
) -> str:
    cmd = ["docker", "exec", mysql_container, "mysql", f"-u{mysql_user}"]
    if mysql_password:
        cmd.append(f"-p{mysql_password}")
    cmd.extend(["-N", "-e", sql])
    proc = run_cmd(cmd, timeout=timeout)
    if proc.returncode != 0:
        raise SuiteError(
            f"MySQL command failed (exit={proc.returncode}), container={mysql_container}, "
            f"stdout={proc.stdout[-500:]}, stderr={proc.stderr[-500:]}"
        )
    return (proc.stdout or "").strip()


def query_mysql_count(
    mysql_container: str,
    mysql_user: str,
    mysql_password: str,
    sql: str,
    timeout: int = 20,
) -> int:
    out = run_mysql_sql(
        mysql_container=mysql_container,
        mysql_user=mysql_user,
        mysql_password=mysql_password,
        sql=sql,
        timeout=timeout,
    )
    lines = [ln.strip() for ln in out.splitlines() if ln.strip()]
    if not lines:
        return 0
    try:
        return int(lines[-1].split()[-1])
    except (ValueError, IndexError) as exc:
        raise SuiteError(f"Failed to parse MySQL count output: {out}") from exc


def sql_escape(raw: str) -> str:
    return raw.replace("\\", "\\\\").replace("'", "''")


def ensure_liquidation_runtime_schema(
    mysql_container: str,
    mysql_user: str,
    mysql_password: str,
) -> dict[str, Any]:
    before_completed = query_mysql_count(
        mysql_container,
        mysql_user,
        mysql_password,
        (
            "SELECT COUNT(*) FROM information_schema.columns "
            "WHERE table_schema='exchange_liquidation' "
            "AND table_name='t_liquidation_execution' AND column_name='completed_at';"
        ),
    )
    before_validated = query_mysql_count(
        mysql_container,
        mysql_user,
        mysql_password,
        (
            "SELECT COUNT(*) FROM information_schema.columns "
            "WHERE table_schema='exchange_liquidation' "
            "AND table_name='t_liquidation_execution' AND column_name='validated_at';"
        ),
    )

    ddl_sql = """
USE exchange_liquidation;
SET @ddl1 = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE t_liquidation_execution ADD COLUMN completed_at BIGINT NULL COMMENT ''完成时间'' AFTER filled_at',
    'SELECT ''completed_at exists'' AS message'
  )
  FROM information_schema.columns
  WHERE table_schema='exchange_liquidation'
    AND table_name='t_liquidation_execution'
    AND column_name='completed_at'
);
PREPARE stmt1 FROM @ddl1;
EXECUTE stmt1;
DEALLOCATE PREPARE stmt1;

SET @ddl2 = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE t_liquidation_execution ADD COLUMN validated_at BIGINT NULL COMMENT ''验证完成时间'' AFTER triggered_at',
    'SELECT ''validated_at exists'' AS message'
  )
  FROM information_schema.columns
  WHERE table_schema='exchange_liquidation'
    AND table_name='t_liquidation_execution'
    AND column_name='validated_at'
);
PREPARE stmt2 FROM @ddl2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
"""
    run_mysql_sql(
        mysql_container=mysql_container,
        mysql_user=mysql_user,
        mysql_password=mysql_password,
        sql=ddl_sql,
        timeout=60,
    )

    after_completed = query_mysql_count(
        mysql_container,
        mysql_user,
        mysql_password,
        (
            "SELECT COUNT(*) FROM information_schema.columns "
            "WHERE table_schema='exchange_liquidation' "
            "AND table_name='t_liquidation_execution' AND column_name='completed_at';"
        ),
    )
    after_validated = query_mysql_count(
        mysql_container,
        mysql_user,
        mysql_password,
        (
            "SELECT COUNT(*) FROM information_schema.columns "
            "WHERE table_schema='exchange_liquidation' "
            "AND table_name='t_liquidation_execution' AND column_name='validated_at';"
        ),
    )

    if after_completed == 0 or after_validated == 0:
        raise SuiteError(
            "Liquidation runtime schema fix did not converge: "
            f"completed_at={after_completed}, validated_at={after_validated}"
        )

    return {
        "mysqlContainer": mysql_container,
        "completedAtBefore": before_completed > 0,
        "validatedAtBefore": before_validated > 0,
        "completedAtAfter": after_completed > 0,
        "validatedAtAfter": after_validated > 0,
    }


def list_active_order_ids(oms_base: str, user_id: int, symbol: str) -> list[str]:
    try:
        _, body = http_json(
            "GET",
            f"{oms_base}/api/v1/oms/order/list?limit=500&status=NEW,PENDING_RISK,FROZEN,PARTIALLY_FILLED&symbol={symbol}",
            headers={"X-User-Id": str(user_id)},
            timeout=10,
        )
    except Exception:
        return []

    seen: set[str] = set()
    order_ids: list[str] = []
    for order in parse_order_list(body):
        oid = order.get("orderId")
        if oid is None:
            continue
        oid_str = str(oid).strip()
        if not oid_str or oid_str in seen:
            continue
        seen.add(oid_str)
        order_ids.append(oid_str)
    return order_ids


def reset_test_state(
    api_gateway: str,
    oms_base: str,
    token: str,
    user_id: int,
    symbol: str,
    timeout_sec: int,
    mysql_container: str,
    mysql_user: str,
    mysql_password: str,
) -> dict[str, Any]:
    active_before = list_active_order_ids(oms_base, user_id, symbol)
    cancel_results: list[dict[str, Any]] = []
    for order_id in active_before:
        cancel_resp = cancel_order(api_gateway, oms_base, token, user_id, order_id)
        final_status, _ = wait_order_status(
            oms_base=oms_base,
            user_id=user_id,
            symbol=symbol,
            order_id=order_id,
            expect={"CANCELED", "FILLED", "REJECTED"},
            timeout_sec=max(10, min(timeout_sec, 25)),
        )
        cancel_results.append(
            {
                "orderId": order_id,
                "finalStatus": final_status,
                "cancelResponse": cancel_resp,
            }
        )

    active_after = list_active_order_ids(oms_base, user_id, symbol)
    if active_after:
        raise SuiteError(f"Still has active orders after reset, userId={user_id}, symbol={symbol}, orderIds={active_after}")

    symbol_sql = sql_escape(symbol.upper())
    cleanup_sql = f"""
DELETE FROM exchange_position.position_snapshot
WHERE user_id = {user_id} AND symbol = '{symbol_sql}';

DELETE FROM exchange_liquidation.t_liquidation_event
WHERE liquidation_id IN (
  SELECT liquidation_id FROM (
    SELECT liquidation_id
    FROM exchange_liquidation.t_liquidation_execution
    WHERE user_id = {user_id} AND symbol = '{symbol_sql}'
  ) x
);

DELETE FROM exchange_liquidation.t_liquidation_execution
WHERE user_id = {user_id} AND symbol = '{symbol_sql}';
"""
    run_mysql_sql(
        mysql_container=mysql_container,
        mysql_user=mysql_user,
        mysql_password=mysql_password,
        sql=cleanup_sql,
        timeout=60,
    )

    position_rows = query_mysql_count(
        mysql_container,
        mysql_user,
        mysql_password,
        f"SELECT COUNT(*) FROM exchange_position.position_snapshot WHERE user_id={user_id} AND symbol='{symbol_sql}';",
    )
    liq_rows = query_mysql_count(
        mysql_container,
        mysql_user,
        mysql_password,
        f"SELECT COUNT(*) FROM exchange_liquidation.t_liquidation_execution WHERE user_id={user_id} AND symbol='{symbol_sql}';",
    )
    event_rows = query_mysql_count(
        mysql_container,
        mysql_user,
        mysql_password,
        (
            "SELECT COUNT(*) "
            "FROM exchange_liquidation.t_liquidation_event e "
            "JOIN exchange_liquidation.t_liquidation_execution x "
            "ON e.liquidation_id = x.liquidation_id "
            f"WHERE x.user_id={user_id} AND x.symbol='{symbol_sql}';"
        ),
    )

    return {
        "userId": user_id,
        "symbol": symbol.upper(),
        "activeOrderCountBefore": len(active_before),
        "canceledOrders": cancel_results,
        "activeOrderCountAfter": len(active_after),
        "positionRowsAfter": position_rows,
        "liquidationRowsAfter": liq_rows,
        "liquidationEventRowsAfter": event_rows,
    }


def publish_kafka_keyed_event(
    kafka_container: str,
    broker: str,
    topic: str,
    key: str,
    event: dict[str, Any],
    timeout: int = 20,
) -> None:
    message = f"{key}:{json.dumps(event, separators=(',', ':'))}"
    cmd = [
        "docker",
        "exec",
        "-e",
        f"KAFKA_BROKER={broker}",
        "-e",
        f"KAFKA_TOPIC={topic}",
        "-e",
        f"KAFKA_MESSAGE={message}",
        kafka_container,
        "sh",
        "-lc",
        (
            "printf '%s\\n' \"$KAFKA_MESSAGE\" | "
            "kafka-console-producer "
            "--bootstrap-server \"$KAFKA_BROKER\" "
            "--topic \"$KAFKA_TOPIC\" "
            "--property parse.key=true "
            "--property key.separator=: "
            "--producer-property acks=1 "
            "--producer-property linger.ms=0 "
            "--producer-property request.timeout.ms=5000 "
            "--producer-property max.block.ms=10000"
        ),
    ]
    proc = run_cmd(cmd, timeout=timeout)
    if proc.returncode != 0:
        raise SuiteError(
            f"Kafka publish failed (exit={proc.returncode}), container={kafka_container}, topic={topic}, "
            f"stdout={proc.stdout[-500:]}, stderr={proc.stderr[-500:]}"
        )


def port_open(port: int) -> bool:
    candidates = ("localhost", "127.0.0.1", "::1")
    for host in candidates:
        try:
            infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
        except OSError:
            continue
        for family, sock_type, proto, _, sockaddr in infos:
            with socket.socket(family, sock_type, proto) as sock:
                sock.settimeout(0.8)
                try:
                    sock.connect(sockaddr)
                    return True
                except OSError:
                    continue
    return False


def wait_port_stable(port: int, timeout_sec: int, stable_sec: int = 2) -> bool:
    timeout = max(1, timeout_sec)
    stable_target = max(1, stable_sec)
    deadline = time.time() + timeout
    stable_hits = 0

    while time.time() < deadline:
        if port_open(port):
            stable_hits += 1
            if stable_hits >= stable_target:
                return True
        else:
            stable_hits = 0
        time.sleep(1)
    return False


def missing_services(required: list[tuple[str, int]]) -> list[tuple[str, int]]:
    return [(name, port) for name, port in required if not port_open(port)]


def ensure_liquidation_ready(
    auto_start: bool,
    probe_timeout_sec: int,
    probe_stable_sec: int,
    restart_attempts: int,
) -> None:
    if wait_port_stable(8102, probe_timeout_sec, probe_stable_sec):
        return

    if not auto_start:
        raise SuiteError("liquidation-core (8102) is not listening")

    attempts = max(1, restart_attempts)
    for idx in range(1, attempts + 1):
        log(f"liquidation-core probe failed, restart attempt {idx}/{attempts}")
        proc = run_cmd(["./scripts/servicectl.sh", "restart", "liquidation-core"], timeout=180)
        if proc.returncode != 0:
            log(
                f"WARN restart liquidation-core failed, exit={proc.returncode}, "
                f"stdout={proc.stdout[-400:]}, stderr={proc.stderr[-400:]}"
            )
        if wait_port_stable(8102, probe_timeout_sec, probe_stable_sec):
            return

    raise SuiteError("liquidation-core (8102) is not listening")


def ensure_services(
    auto_start: bool,
    with_liquidation: bool,
    service_probe_timeout_sec: int,
    liquidation_probe_timeout_sec: int,
    liquidation_probe_stable_sec: int,
    liquidation_restart_attempts: int,
) -> None:
    required: list[tuple[str, int]] = [
        ("api-gateway", 8082),
        ("oms-core", 8081),
        ("match-engine-core", 8083),
        ("ledger-core", 8084),
        ("position-snapshot-core", 8086),
        ("user-core", 8100),
        ("private-push-core", 8097),
    ]
    if with_liquidation:
        required.append(("liquidation-core", 8102))

    # Probe window: avoid transient startup blips causing false negatives.
    probe_deadline = time.time() + max(1, service_probe_timeout_sec)
    while time.time() < probe_deadline:
        if not missing_services(required):
            if with_liquidation:
                ensure_liquidation_ready(
                    auto_start,
                    liquidation_probe_timeout_sec,
                    liquidation_probe_stable_sec,
                    liquidation_restart_attempts,
                )
            return
        time.sleep(1)

    missing = missing_services(required)
    if not missing:
        if with_liquidation:
            ensure_liquidation_ready(
                auto_start,
                liquidation_probe_timeout_sec,
                liquidation_probe_stable_sec,
                liquidation_restart_attempts,
            )
        return

    if not auto_start:
        raise SuiteError(f"Required services are not listening: {missing}")

    log(f"Missing services detected, starting: {', '.join(name for name, _ in missing)}")
    for name, _ in missing:
        proc = run_cmd(["./scripts/servicectl.sh", "start", name], timeout=120)
        if proc.returncode != 0:
            raise SuiteError(
                f"Failed to start {name}, exit={proc.returncode}, "
                f"stdout={proc.stdout[-600:]}, stderr={proc.stderr[-600:]}"
            )

    # wait
    deadline = time.time() + 60
    while time.time() < deadline:
        still = missing_services(required)
        if not still:
            if with_liquidation:
                ensure_liquidation_ready(
                    auto_start,
                    liquidation_probe_timeout_sec,
                    liquidation_probe_stable_sec,
                    liquidation_restart_attempts,
                )
            return
        time.sleep(1)

    still = missing_services(required)
    raise SuiteError(f"Services still missing after auto-start: {still}")


def http_json(
    method: str,
    url: str,
    payload: dict[str, Any] | None = None,
    headers: dict[str, str] | None = None,
    timeout: int = 10,
) -> tuple[int, Any]:
    req_headers = dict(headers or {})
    body = None
    if payload is not None:
        req_headers["Content-Type"] = "application/json"
        body = json.dumps(payload).encode("utf-8")

    req = Request(url=url, method=method, data=body, headers=req_headers)
    ssl_ctx = None
    if url.startswith("https://"):
        ssl_ctx = ssl._create_unverified_context()

    try:
        with urlopen(req, timeout=timeout, context=ssl_ctx) as resp:
            raw = resp.read().decode("utf-8")
            status = int(resp.getcode())
    except HTTPError as exc:
        text = exc.read().decode("utf-8", errors="replace")
        raise SuiteError(f"HTTP {exc.code} {method} {url}: {text}") from exc
    except URLError as exc:
        raise SuiteError(f"HTTP {method} {url} failed: {exc}") from exc

    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise SuiteError(f"Non-JSON response from {method} {url}: {raw[:240]}") from exc
    return status, parsed


def get_nested(obj: Any, *keys: str) -> Any:
    cur = obj
    for key in keys:
        if not isinstance(cur, dict):
            return None
        cur = cur.get(key)
    return cur


def to_decimal(value: Any, field: str) -> Decimal:
    try:
        return Decimal(str(value))
    except (InvalidOperation, TypeError) as exc:
        raise SuiteError(f"Invalid decimal for {field}: {value}") from exc


def dec_to_int_str(v: Decimal) -> str:
    scaled = (v * SCALE).quantize(Decimal("1"), rounding=ROUND_HALF_UP)
    return str(int(scaled))


def int_to_dec(v: int | str) -> Decimal:
    return Decimal(str(v)) / SCALE


def auth_headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def parse_order_id(resp: Any) -> str | None:
    if isinstance(resp, dict):
        for path in (("orderId",), ("data", "orderId")):
            raw = get_nested(resp, *path)
            if raw is not None and str(raw).strip() and str(raw).lower() != "null":
                return str(raw)
    return None


def parse_order_list(resp: Any) -> list[dict[str, Any]]:
    if not isinstance(resp, dict):
        return []
    orders = resp.get("orders")
    if isinstance(orders, list):
        return [o for o in orders if isinstance(o, dict)]
    data = resp.get("data")
    if isinstance(data, dict) and isinstance(data.get("orders"), list):
        return [o for o in data["orders"] if isinstance(o, dict)]
    if isinstance(data, list):
        return [o for o in data if isinstance(o, dict)]
    return []


def parse_positions(body: Any) -> list[dict[str, Any]]:
    if not isinstance(body, dict):
        return []
    if isinstance(body.get("positions"), list):
        return body["positions"]
    data = body.get("data")
    if isinstance(data, dict) and isinstance(data.get("positions"), list):
        return data["positions"]
    if isinstance(data, list):
        return data
    return []


def login(api_gateway: str, username: str, password: str) -> tuple[str, int]:
    _, body = http_json(
        "POST",
        f"{api_gateway}/api/v1/user/login",
        payload={"username": username, "password": password},
        timeout=10,
    )
    token = get_nested(body, "data", "token") or get_nested(body, "data", "accessToken")
    user_id = get_nested(body, "data", "userId") or get_nested(body, "data", "id")
    if not token or user_id is None:
        raise SuiteError(f"Login failed: {body}")
    return str(token), int(user_id)


def get_positions(api_gateway: str, token: str) -> list[dict[str, Any]]:
    _, body = http_json("GET", f"{api_gateway}/api/v1/position/list", headers=auth_headers(token), timeout=10)
    return parse_positions(body)


def get_depth(match_base: str, symbol: str) -> dict[str, Any]:
    _, body = http_json("GET", f"{match_base}/api/v1/match/orderbook/depth/{symbol}?depth=20", timeout=10)
    if not isinstance(body, dict):
        raise SuiteError(f"Invalid depth response: {body}")
    return body


def fetch_fallback_bid_ask(api_gateway: str, symbol: str) -> tuple[Decimal, Decimal, str]:
    try:
        _, body = http_json("GET", f"{api_gateway}/api/v1/bookTicker?symbol={symbol}", timeout=10)
        data = body.get("data") if isinstance(body, dict) else None
        if isinstance(data, dict):
            bid = to_decimal(data.get("bidPrice"), "bookTicker.bidPrice")
            ask = to_decimal(data.get("askPrice"), "bookTicker.askPrice")
            if bid > 0 and ask > 0:
                return bid, ask, "bookTicker"
    except Exception:
        pass

    try:
        _, body = http_json("GET", f"{api_gateway}/api/v1/ticker/24hr?symbol={symbol}", timeout=10)
        data = body.get("data") if isinstance(body, dict) else None
        if isinstance(data, dict):
            last_raw = data.get("lastPrice") or data.get("closePrice")
            if last_raw is not None:
                last = to_decimal(last_raw, "ticker24h.lastPrice")
                if last > 0:
                    bid = (last * Decimal("0.999")).quantize(Decimal("0.00000001"), rounding=ROUND_HALF_UP)
                    ask = (last * Decimal("1.001")).quantize(Decimal("0.00000001"), rounding=ROUND_HALF_UP)
                    return bid, ask, "ticker24h"
    except Exception:
        pass

    anchor_raw = os.getenv("ACCEPTANCE_FALLBACK_PRICE", "70000")
    try:
        anchor = to_decimal(anchor_raw, "ACCEPTANCE_FALLBACK_PRICE")
    except Exception:
        anchor = Decimal("70000")
    if anchor <= 0:
        anchor = Decimal("70000")
    bid = (anchor * Decimal("0.999")).quantize(Decimal("0.00000001"), rounding=ROUND_HALF_UP)
    ask = (anchor * Decimal("1.001")).quantize(Decimal("0.00000001"), rounding=ROUND_HALF_UP)
    return bid, ask, "default"


def best_bid_ask(depth: dict[str, Any], fallback: tuple[Decimal, Decimal] | None = None) -> tuple[Decimal, Decimal]:
    bids = depth.get("bids") or []
    asks = depth.get("asks") or []
    if bids and asks:
        bid = to_decimal(bids[0][0], "bestBid")
        ask = to_decimal(asks[0][0], "bestAsk")
    elif bids:
        bid = to_decimal(bids[0][0], "bestBid")
        ask = (bid * Decimal("1.01")).quantize(Decimal("0.00000001"), rounding=ROUND_HALF_UP)
    elif asks:
        ask = to_decimal(asks[0][0], "bestAsk")
        bid = (ask * Decimal("0.99")).quantize(Decimal("0.00000001"), rounding=ROUND_HALF_UP)
    else:
        if fallback is None:
            raise SuiteError(f"Depth has no bids/asks: {depth}")
        bid, ask = fallback
    if bid <= 0 or ask <= 0:
        raise SuiteError(f"Invalid best bid/ask: bid={bid}, ask={ask}")
    return bid, ask


def submit_order(
    api_gateway: str,
    oms_base: str,
    token: str,
    user_id: int,
    payload: dict[str, Any],
    idempotency_key: str,
) -> str:
    gateway_error = None
    # First try through gateway.
    try:
        _, body = http_json(
            "POST",
            f"{api_gateway}/api/order/create",
            payload=payload,
            headers={**auth_headers(token), "X-Idempotency-Key": idempotency_key},
            timeout=10,
        )
        oid = parse_order_id(body)
        if oid:
            return oid
    except Exception as exc:
        gateway_error = str(exc)
        body = {"error": gateway_error}

    client_order_id = str(payload.get("clientOrderId", "")).strip()

    def find_order_by_client_order_id() -> str | None:
        if not client_order_id:
            return None
        for status_expr in ("NEW,PENDING_RISK,FROZEN,PARTIALLY_FILLED", "FILLED,CANCELED,REJECTED"):
            try:
                _, list_body = http_json(
                    "GET",
                    f"{oms_base}/api/v1/oms/order/list?limit=100&status={status_expr}&symbol={payload.get('symbol', '')}",
                    headers={"X-User-Id": str(user_id)},
                    timeout=10,
                )
            except Exception:
                continue
            for order in parse_order_list(list_body):
                if str(order.get("clientOrderId", "")) == client_order_id:
                    found = order.get("orderId")
                    if found is not None and str(found).strip():
                        return str(found)
        return None

    # Fallback to OMS direct submit.
    headers = {
        "X-User-Id": str(user_id),
        "X-Trace-Id": f"trace-{int(time.time() * 1000)}",
        "X-Request-Id": f"req-{int(time.time() * 1000)}",
    }
    oms_body: Any = None
    oms_error: str | None = None
    for i in range(3):
        try:
            _, oms_body = http_json(
                "POST",
                f"{oms_base}/api/v1/oms/order/submit",
                payload=payload,
                headers=headers,
                timeout=10,
            )
            oid = parse_order_id(oms_body)
            if oid:
                return oid
        except Exception as exc:
            oms_error = str(exc)

        found_oid = find_order_by_client_order_id()
        if found_oid:
            return found_oid

        err_code = ""
        if isinstance(oms_body, dict):
            err_code = str(oms_body.get("errorCode", ""))
        # Retry transient server-side exception, otherwise break early.
        if err_code != "OMS_9001" and not (oms_error and "503" in oms_error):
            break
        time.sleep(1)

    raise SuiteError(
        f"Order submit failed. gateway={body}, gatewayErr={gateway_error}, oms={oms_body}, omsErr={oms_error}"
    )


def query_order(oms_base: str, user_id: int, symbol: str, order_id: str) -> dict[str, Any]:
    target = str(order_id)
    for status_expr in ("NEW,PENDING_RISK,FROZEN,PARTIALLY_FILLED", "FILLED,CANCELED,REJECTED"):
        try:
            _, body = http_json(
                "GET",
                f"{oms_base}/api/v1/oms/order/list?limit=100&status={status_expr}&symbol={symbol}",
                headers={"X-User-Id": str(user_id)},
                timeout=10,
            )
        except Exception:
            continue
        for order in parse_order_list(body):
            if str(order.get("orderId", "")) == target:
                return order
    return {}


def order_status(order_obj: dict[str, Any]) -> str:
    raw = order_obj.get("status")
    return str(raw).upper() if raw is not None else ""


def wait_order_status(
    oms_base: str,
    user_id: int,
    symbol: str,
    order_id: str,
    expect: set[str],
    timeout_sec: int,
) -> tuple[str, dict[str, Any]]:
    deadline = time.time() + timeout_sec
    last_obj: dict[str, Any] = {}
    while time.time() < deadline:
        obj = query_order(oms_base, user_id, symbol, order_id)
        last_obj = obj
        st = order_status(obj)
        if st in expect:
            return st, obj
        time.sleep(1)
    return order_status(last_obj), last_obj


def cancel_order(api_gateway: str, oms_base: str, token: str, user_id: int, order_id: str) -> dict[str, Any]:
    # Gateway path first.
    try:
        _, body = http_json(
            "POST",
            f"{api_gateway}/api/v1/oms/order/cancel",
            payload={"orderId": order_id},
            headers=auth_headers(token),
            timeout=10,
        )
        return body if isinstance(body, dict) else {"raw": body}
    except Exception:
        pass

    # OMS direct fallback.
    _, body = http_json(
        "POST",
        f"{oms_base}/api/v1/oms/order/cancel",
        payload={"orderId": order_id},
        headers={
            "X-User-Id": str(user_id),
            "X-Trace-Id": f"trace-cancel-{int(time.time() * 1000)}",
            "X-Request-Id": f"req-cancel-{int(time.time() * 1000)}",
        },
        timeout=10,
    )
    return body if isinstance(body, dict) else {"raw": body}


def pick_active_position(positions: list[dict[str, Any]], symbol: str) -> dict[str, Any] | None:
    active: list[dict[str, Any]] = []
    for p in positions:
        if str(p.get("symbol", "")).upper() != symbol.upper():
            continue
        try:
            size = to_decimal(p.get("size", "0"), "position.size")
        except SuiteError:
            continue
        if size > 0:
            active.append(p)
    if not active:
        return None
    # choose largest by size
    return sorted(active, key=lambda x: to_decimal(x.get("size", "0"), "position.size"), reverse=True)[0]


def summarize_position(p: dict[str, Any]) -> dict[str, Any]:
    return {
        "symbol": p.get("symbol"),
        "side": p.get("side"),
        "positionSide": p.get("positionSide"),
        "size": str(p.get("size")),
        "entryPrice": str(p.get("entryPrice")),
        "unrealizedPnl": str(p.get("unrealizedPnl")),
        "realizedPnl": str(p.get("realizedPnl")),
        "marginRatio": str(p.get("marginRatio")),
        "liquidationPrice": str(p.get("liquidationPrice")),
    }


def make_aggressive_price(side: str, best_bid: Decimal, best_ask: Decimal) -> Decimal:
    s = side.upper()
    if s == "BUY":
        return (best_ask * Decimal("1.01")).quantize(Decimal("0.00000001"), rounding=ROUND_HALF_UP)
    return (best_bid * Decimal("0.99")).quantize(Decimal("0.00000001"), rounding=ROUND_HALF_UP)


def make_passive_price(side: str, best_bid: Decimal, best_ask: Decimal) -> Decimal:
    s = side.upper()
    if s == "BUY":
        return (best_bid * Decimal("0.90")).quantize(Decimal("0.00000001"), rounding=ROUND_HALF_UP)
    return (best_ask * Decimal("1.10")).quantize(Decimal("0.00000001"), rounding=ROUND_HALF_UP)


def make_pair_price_for_taker(side: str, best_bid: Decimal, best_ask: Decimal) -> Decimal:
    # Use an obvious aggressive level so the taker side can reliably consume a seeded opposite order.
    s = side.upper()
    if s == "BUY":
        return (best_ask * Decimal("1.20")).quantize(Decimal("0.00000001"), rounding=ROUND_HALF_UP)
    return (best_bid * Decimal("0.80")).quantize(Decimal("0.00000001"), rounding=ROUND_HALF_UP)


def make_liquidation_seed_price(close_side: str, best_bid: Decimal, best_ask: Decimal) -> Decimal:
    # Keep seed order resting in book while making it top-of-book for taker liquidation order.
    tick = Decimal("0.00000001")
    side = close_side.upper()
    if side == "SELL":
        candidate = (best_bid + tick).quantize(Decimal("0.00000001"), rounding=ROUND_HALF_UP)
        ceiling = (best_ask - tick).quantize(Decimal("0.00000001"), rounding=ROUND_HALF_UP)
        if ceiling > 0 and candidate < ceiling:
            return candidate
        return best_bid.quantize(Decimal("0.00000001"), rounding=ROUND_HALF_UP)

    candidate = (best_ask - tick).quantize(Decimal("0.00000001"), rounding=ROUND_HALF_UP)
    floor = (best_bid + tick).quantize(Decimal("0.00000001"), rounding=ROUND_HALF_UP)
    if candidate > floor:
        return candidate
    return best_ask.quantize(Decimal("0.00000001"), rounding=ROUND_HALF_UP)


def depth_counterparty_qty(depth: dict[str, Any], close_side: str, seed_price: Decimal) -> Decimal:
    # close_side means liquidation taker side.
    # SELL taker consumes bids priced >= seed_price; BUY taker consumes asks priced <= seed_price.
    side = close_side.upper()
    levels = (depth.get("bids") or []) if side == "SELL" else (depth.get("asks") or [])
    total = Decimal("0")
    for idx, level in enumerate(levels):
        if not isinstance(level, list) or len(level) < 2:
            continue
        px = to_decimal(level[0], f"depth.level[{idx}].price")
        qty = to_decimal(level[1], f"depth.level[{idx}].qty")
        if side == "SELL":
            if px >= seed_price:
                total += qty
        else:
            if px <= seed_price:
                total += qty
    return total


def compute_liq_expected(entry: Decimal, side: str, leverage: Decimal = DEFAULT_LEVERAGE) -> Decimal:
    one = Decimal("1")
    lev_factor = one / leverage
    if side.upper() == "LONG":
        numerator = entry * (one - lev_factor)
        denominator = one - MAINTENANCE_MARGIN_RATE
    else:
        numerator = entry * (one + lev_factor)
        denominator = one + MAINTENANCE_MARGIN_RATE
    return (numerator / denominator).quantize(Decimal("0.00000001"), rounding=ROUND_HALF_UP)


def wait_active_position(
    api_gateway: str,
    token: str,
    symbol: str,
    timeout_sec: int,
) -> dict[str, Any] | None:
    deadline = time.time() + timeout_sec
    while time.time() < deadline:
        positions = get_positions(api_gateway, token)
        target = pick_active_position(positions, symbol)
        if target:
            return target
        time.sleep(1)
    return None


def run_pnl_push_check(
    symbol: str,
    username: str,
    password: str,
    api_gateway: str,
    private_ws: str,
    retries: int,
    kafka_container: str,
    kafka_broker: str,
    mark_topic: str,
) -> StepResult:
    attempts: list[dict[str, Any]] = []
    max_attempts = max(1, retries)

    for attempt in range(1, max_attempts + 1):
        env = os.environ.copy()
        env["SYMBOL"] = symbol
        env["USERNAME"] = username
        env["PASSWORD"] = password
        env["API_GATEWAY"] = api_gateway
        env["PRIVATE_WS"] = private_ws
        env["LOGIN_URL"] = f"{api_gateway}/api/v1/user/login"
        env["POSITION_URL"] = f"{api_gateway}/api/v1/position/list"
        env["KAFKA_CONTAINER"] = kafka_container
        env["KAFKA_BROKER"] = kafka_broker
        env["MARK_TOPIC"] = mark_topic

        proc = run_cmd(["python3", "scripts/test_position_up_mark_push.py"], timeout=120, env=env)
        out = (proc.stdout or "").strip()
        err = (proc.stderr or "").strip()

        baseline = None
        current = None
        mark_price = None
        for line in out.splitlines()[::-1]:
            line = line.strip()
            if not line.startswith("{"):
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            baseline = obj.get("baselineUp")
            current = obj.get("currentUp")
            mark_price = obj.get("markPrice")
            break

        attempt_info = {
            "attempt": attempt,
            "returncode": proc.returncode,
            "baselineUp": baseline,
            "currentUp": current,
            "markPrice": mark_price,
            "stdout_tail": out[-600:],
            "stderr_tail": err[-600:],
        }
        attempts.append(attempt_info)

        if proc.returncode != 0:
            continue
        if baseline is None or current is None:
            continue
        if Decimal(str(baseline)) == Decimal(str(current)):
            continue

        return StepResult(
            name="pnl_push",
            ok=True,
            message=f"PNL changed after mark injection (attempt {attempt}/{max_attempts})",
            details={
                "attempt": attempt,
                "baselineUp": baseline,
                "currentUp": current,
                "markPrice": mark_price,
            },
        )

    return StepResult(
        name="pnl_push",
        ok=False,
        message=f"PNL push script failed after {max_attempts} attempt(s)",
        details={"attempts": attempts},
    )


def fetch_internal_position(
    position_internal_base: str,
    user_id: int,
    symbol: str,
    position_side: int,
) -> dict[str, Any] | None:
    url = f"{position_internal_base}/internal/position/{user_id}/{symbol}?positionSide={position_side}"
    try:
        _, body = http_json("GET", url, timeout=10)
    except Exception:
        return None
    return body if isinstance(body, dict) else None


def inject_liquidation_trigger(
    symbol: str,
    event: dict[str, Any],
    kafka_container: str,
    broker: str,
    topic: str,
) -> None:
    publish_kafka_keyed_event(
        kafka_container=kafka_container,
        broker=broker,
        topic=topic,
        key=symbol,
        event=event,
        timeout=20,
    )


def write_reports(report_dir: Path, steps: list[StepResult], started_at: float) -> tuple[Path, Path]:
    report_dir.mkdir(parents=True, exist_ok=True)
    ts = time.strftime("%Y%m%d-%H%M%S")
    md_path = report_dir / f"acceptance-suite-{ts}.md"
    json_path = report_dir / f"acceptance-suite-{ts}.json"
    elapsed = round(time.time() - started_at, 2)
    passed = sum(1 for s in steps if s.ok)
    failed = sum(1 for s in steps if not s.ok)

    lines: list[str] = []
    lines.append("# E2E Acceptance Suite Report")
    lines.append("")
    lines.append(f"- Time: {time.strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"- DurationSec: {elapsed}")
    lines.append(f"- Passed: {passed}")
    lines.append(f"- Failed: {failed}")
    lines.append("")
    lines.append("| Step | Result | Message |")
    lines.append("|------|--------|---------|")
    for s in steps:
        lines.append(f"| {s.name} | {'PASS' if s.ok else 'FAIL'} | {s.message} |")
    lines.append("")
    lines.append("## Details")
    lines.append("")
    for s in steps:
        lines.append(f"### {s.name}")
        lines.append(f"- Result: {'PASS' if s.ok else 'FAIL'}")
        lines.append(f"- Message: {s.message}")
        lines.append("- Details:")
        lines.append("```json")
        lines.append(json.dumps(s.details, ensure_ascii=False, indent=2))
        lines.append("```")
        lines.append("")

    md_path.write_text("\n".join(lines), encoding="utf-8")
    payload = {
        "generatedAt": int(time.time() * 1000),
        "durationSec": elapsed,
        "passed": passed,
        "failed": failed,
        "steps": [asdict(s) for s in steps],
    }
    json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return md_path, json_path


def main() -> int:
    disable_proxy_for_local()
    args = parse_args()
    started_at = time.time()
    steps: list[StepResult] = []

    if not args.skip_runtime_fix:
        try:
            schema_fix_details = ensure_liquidation_runtime_schema(
                mysql_container=args.mysql_container,
                mysql_user=args.mysql_user,
                mysql_password=args.mysql_password,
            )
            steps.append(
                StepResult(
                    name="runtime_schema_fix",
                    ok=True,
                    message="Liquidation runtime schema aligned",
                    details=schema_fix_details,
                )
            )
        except Exception as exc:
            steps.append(
                StepResult(
                    name="runtime_schema_fix",
                    ok=False,
                    message="Runtime schema fix failed",
                    details={"error": str(exc)},
                )
            )
            md, js = write_reports(Path(args.report_dir), steps, started_at)
            log(f"FAIL runtime_schema_fix: {exc}")
            log(f"Report: {md}")
            log(f"Details: {js}")
            return 1

    try:
        ensure_services(
            args.auto_start,
            args.with_liquidation,
            args.service_probe_timeout_sec,
            args.liquidation_probe_timeout_sec,
            args.liquidation_probe_stable_sec,
            args.liquidation_restart_attempts,
        )
        steps.append(
            StepResult(
                name="service_check",
                ok=True,
                message="Required services are listening",
                details={
                    "apiGateway": args.api_gateway,
                    "omsBase": args.oms_base,
                    "matchBase": args.match_base,
                    "positionInternalBase": args.position_internal_base,
                    "liquidationBase": args.liquidation_base,
                },
            )
        )
    except Exception as exc:
        steps.append(StepResult(name="service_check", ok=False, message="Service check failed", details={"error": str(exc)}))
        md, js = write_reports(Path(args.report_dir), steps, started_at)
        log(f"FAIL service_check: {exc}")
        log(f"Report: {md}")
        log(f"Details: {js}")
        return 1

    try:
        token, user_id = login(args.api_gateway, args.username, args.password)
        steps.append(
            StepResult(
                name="login",
                ok=True,
                message="Login success",
                details={"userId": user_id, "tokenLen": len(token)},
            )
        )
    except Exception as exc:
        steps.append(StepResult(name="login", ok=False, message="Login failed", details={"error": str(exc)}))
        md, js = write_reports(Path(args.report_dir), steps, started_at)
        log(f"FAIL login: {exc}")
        log(f"Report: {md}")
        log(f"Details: {js}")
        return 1

    try:
        maker_token, maker_user_id = login(args.api_gateway, args.maker_username, args.maker_password)
        if maker_user_id == user_id:
            raise SuiteError(
                f"Maker user must be different from test user. makerUserId={maker_user_id}, userId={user_id}"
            )
        steps.append(
            StepResult(
                name="maker_login",
                ok=True,
                message="Maker account login success",
                details={
                    "makerUsername": args.maker_username,
                    "makerUserId": maker_user_id,
                    "tokenLen": len(maker_token),
                },
            )
        )
    except Exception as exc:
        steps.append(
            StepResult(
                name="maker_login",
                ok=False,
                message="Maker account login failed",
                details={"error": str(exc)},
            )
        )
        md, js = write_reports(Path(args.report_dir), steps, started_at)
        log(f"FAIL maker_login: {exc}")
        log(f"Report: {md}")
        log(f"Details: {js}")
        return 1

    if not args.skip_test_state_reset:
        try:
            reset_details = reset_test_state(
                api_gateway=args.api_gateway,
                oms_base=args.oms_base,
                token=token,
                user_id=user_id,
                symbol=args.symbol,
                timeout_sec=args.timeout_sec,
                mysql_container=args.mysql_container,
                mysql_user=args.mysql_user,
                mysql_password=args.mysql_password,
            )
            steps.append(
                StepResult(
                    name="test_state_reset",
                    ok=True,
                    message="Test account state reset to clean baseline",
                    details=reset_details,
                )
            )
        except Exception as exc:
            steps.append(
                StepResult(
                    name="test_state_reset",
                    ok=False,
                    message="Test state reset failed",
                    details={"error": str(exc)},
                )
            )
            md, js = write_reports(Path(args.report_dir), steps, started_at)
            log(f"FAIL test_state_reset: {exc}")
            log(f"Report: {md}")
            log(f"Details: {js}")
            return 1

        try:
            maker_reset_details = reset_test_state(
                api_gateway=args.api_gateway,
                oms_base=args.oms_base,
                token=maker_token,
                user_id=maker_user_id,
                symbol=args.symbol,
                timeout_sec=args.timeout_sec,
                mysql_container=args.mysql_container,
                mysql_user=args.mysql_user,
                mysql_password=args.mysql_password,
            )
            steps.append(
                StepResult(
                    name="maker_state_reset",
                    ok=True,
                    message="Maker account state reset to clean baseline",
                    details=maker_reset_details,
                )
            )
        except Exception as exc:
            steps.append(
                StepResult(
                    name="maker_state_reset",
                    ok=False,
                    message="Maker state reset failed",
                    details={"error": str(exc)},
                )
            )
            md, js = write_reports(Path(args.report_dir), steps, started_at)
            log(f"FAIL maker_state_reset: {exc}")
            log(f"Report: {md}")
            log(f"Details: {js}")
            return 1

    # Prepare baseline position/depth context.
    try:
        positions0 = get_positions(args.api_gateway, token)
        pos0 = pick_active_position(positions0, args.symbol)
        fallback_bid, fallback_ask, fallback_source = fetch_fallback_bid_ask(args.api_gateway, args.symbol)
        depth = get_depth(args.match_base, args.symbol)
        depth_has_levels = bool((depth.get("bids") or []) or (depth.get("asks") or []))
        best_bid, best_ask = best_bid_ask(depth, fallback=(fallback_bid, fallback_ask))
        steps.append(
            StepResult(
                name="context_snapshot",
                ok=True,
                message=(
                    "Fetched positions and orderbook depth"
                    if depth_has_levels
                    else f"Depth empty, fallback bid/ask from {fallback_source}"
                ),
                details={
                    "bestBid": str(best_bid),
                    "bestAsk": str(best_ask),
                    "depthHasLevels": depth_has_levels,
                    "fallbackSource": fallback_source,
                    "activePosition": summarize_position(pos0) if pos0 else None,
                    "positionCount": len(positions0),
                },
            )
        )
    except Exception as exc:
        steps.append(StepResult(name="context_snapshot", ok=False, message="Failed to fetch context", details={"error": str(exc)}))
        md, js = write_reports(Path(args.report_dir), steps, started_at)
        log(f"FAIL context_snapshot: {exc}")
        log(f"Report: {md}")
        log(f"Details: {js}")
        return 1

    # Decide open side for controlled increase/close cycle.
    open_side = (str(pos0.get("side")).upper() if pos0 else "BUY")
    if open_side not in {"BUY", "SELL", "LONG", "SHORT"}:
        open_side = "BUY"
    if open_side == "LONG":
        open_side = "BUY"
    elif open_side == "SHORT":
        open_side = "SELL"
    close_side = "SELL" if open_side == "BUY" else "BUY"

    # Step: cancel flow.
    try:
        cancel_price = make_passive_price(close_side, best_bid, best_ask)
        cancel_payload = {
            "symbol": args.symbol,
            "side": close_side,
            "type": "LIMIT",
            "price": dec_to_int_str(cancel_price),
            "quantity": str(args.cancel_qty_int),
            "leverage": 10,
            "timeInForce": "GTC",
            "clientOrderId": f"acc-cancel-{int(time.time() * 1000)}",
        }
        oid = submit_order(
            api_gateway=args.api_gateway,
            oms_base=args.oms_base,
            token=token,
            user_id=user_id,
            payload=cancel_payload,
            idempotency_key=f"idem-{cancel_payload['clientOrderId']}",
        )
        # quick check not immediately filled.
        st_early, obj_early = wait_order_status(
            args.oms_base,
            user_id,
            args.symbol,
            oid,
            {"NEW", "PENDING_RISK", "FROZEN", "PARTIALLY_FILLED", "FILLED"},
            10,
        )
        if st_early == "FILLED":
            raise SuiteError(f"Cancel test order unexpectedly FILLED, orderId={oid}, query={obj_early}")

        cancel_resp = cancel_order(args.api_gateway, args.oms_base, token, user_id, oid)
        st_final, obj_final = wait_order_status(
            args.oms_base,
            user_id,
            args.symbol,
            oid,
            {"CANCELED", "REJECTED"},
            args.timeout_sec,
        )
        if st_final != "CANCELED":
            raise SuiteError(f"Cancel verification failed, status={st_final}, query={obj_final}, cancelResp={cancel_resp}")

        steps.append(
            StepResult(
                name="cancel_flow",
                ok=True,
                message="Passive order canceled successfully",
                details={
                    "orderId": oid,
                    "side": close_side,
                    "price": str(cancel_price),
                    "cancelResponse": cancel_resp,
                    "finalStatus": st_final,
                },
            )
        )
    except Exception as exc:
        steps.append(StepResult(name="cancel_flow", ok=False, message="Cancel flow failed", details={"error": str(exc)}))

    # Step: open aggressive fill.
    pre_positions_for_fill = get_positions(args.api_gateway, token)
    pre_target = pick_active_position(pre_positions_for_fill, args.symbol)
    pre_size = to_decimal(pre_target.get("size", "0"), "pre_size") if pre_target else Decimal("0")
    try:
        paired_open_price = make_pair_price_for_taker(open_side, best_bid, best_ask)
        seed_side = "SELL" if open_side == "BUY" else "BUY"
        seed_payload = {
            "symbol": args.symbol,
            "side": seed_side,
            "type": "LIMIT",
            "price": dec_to_int_str(paired_open_price),
            "quantity": str(args.trade_qty_int),
            "leverage": 10,
            "timeInForce": "GTC",
            "clientOrderId": f"acc-seed-open-{int(time.time() * 1000)}",
        }
        seed_oid = submit_order(
            api_gateway=args.api_gateway,
            oms_base=args.oms_base,
            token=maker_token,
            user_id=maker_user_id,
            payload=seed_payload,
            idempotency_key=f"idem-{seed_payload['clientOrderId']}",
        )
        seed_st_init, seed_obj_init = wait_order_status(
            args.oms_base,
            maker_user_id,
            args.symbol,
            seed_oid,
            ACTIVE_ORDER_STATUSES | {"FILLED", "CANCELED", "REJECTED"},
            15,
        )
        seed_filled_init = to_decimal(seed_obj_init.get("filledQuantity", "0"), "seed.filledQuantity")
        if seed_st_init in {"REJECTED", "CANCELED"} and seed_filled_init <= 0:
            raise SuiteError(f"Seed order failed before taker submit, status={seed_st_init}, query={seed_obj_init}")

        open_payload = {
            "symbol": args.symbol,
            "side": open_side,
            "type": "LIMIT",
            "price": dec_to_int_str(paired_open_price),
            "quantity": str(args.trade_qty_int),
            "leverage": 10,
            "timeInForce": "GTC",
            "clientOrderId": f"acc-open-{int(time.time() * 1000)}",
        }
        open_oid = submit_order(
            api_gateway=args.api_gateway,
            oms_base=args.oms_base,
            token=token,
            user_id=user_id,
            payload=open_payload,
            idempotency_key=f"idem-{open_payload['clientOrderId']}",
        )
        st_fill, obj_fill = wait_order_status(
            args.oms_base,
            user_id,
            args.symbol,
            open_oid,
            {"FILLED", "PARTIALLY_FILLED", "REJECTED", "CANCELED"},
            args.timeout_sec,
        )
        if st_fill not in {"FILLED", "PARTIALLY_FILLED"}:
            raise SuiteError(f"Aggressive open order not FILLED, status={st_fill}, query={obj_fill}")

        seed_st_final, seed_obj_final = wait_order_status(
            args.oms_base,
            maker_user_id,
            args.symbol,
            seed_oid,
            ACTIVE_ORDER_STATUSES | {"FILLED", "CANCELED", "REJECTED"},
            15,
        )
        if seed_st_final in ACTIVE_ORDER_STATUSES:
            cancel_order(args.api_gateway, args.oms_base, maker_token, maker_user_id, seed_oid)
            seed_st_final, seed_obj_final = wait_order_status(
                args.oms_base,
                maker_user_id,
                args.symbol,
                seed_oid,
                {"FILLED", "CANCELED", "REJECTED"},
                15,
            )

        fill_details = {
            "orderId": open_oid,
            "side": open_side,
            "price": str(paired_open_price),
            "qtyInt": args.trade_qty_int,
            "finalStatus": st_fill,
            "seedOrderId": seed_oid,
            "seedOrderSide": seed_side,
            "seedOrderStatus": seed_st_final,
            "preSize": str(pre_size),
        }
        if st_fill == "PARTIALLY_FILLED":
            filled_qty = to_decimal(obj_fill.get("filledQuantity", "0"), "filledQuantity")
            if filled_qty <= 0:
                raise SuiteError(f"Order PARTIALLY_FILLED but filledQuantity<=0, query={obj_fill}")
            # Cancel residual quantity to avoid leaving dust order in book.
            cancel_order(args.api_gateway, args.oms_base, token, user_id, open_oid)
            fill_details["filledQuantity"] = str(filled_qty)
            fill_details["note"] = "Partial fill accepted on thin book; residual canceled"
        seed_filled_final = to_decimal(seed_obj_final.get("filledQuantity", "0"), "seed.filledQuantityFinal")
        fill_details["seedFilledQuantity"] = str(seed_filled_final)

        target_after_fill = wait_active_position(args.api_gateway, token, args.symbol, max(10, min(args.timeout_sec, 20)))
        if not target_after_fill:
            raise SuiteError("No active position found after fill")
        fill_details["postFillPosition"] = summarize_position(target_after_fill)
        steps.append(
            StepResult(
                name="fill_flow",
                ok=True,
                message="Aggressive order matched with seeded counterparty",
                details=fill_details,
            )
        )
    except Exception as exc:
        steps.append(StepResult(name="fill_flow", ok=False, message="Fill flow failed", details={"error": str(exc)}))

    # Step: manual close (partial close to keep position alive for later checks).
    try:
        target_after_fill = wait_active_position(args.api_gateway, token, args.symbol, max(10, min(args.timeout_sec, 20)))
        if not target_after_fill:
            raise SuiteError("No active position after fill")
        side_after_fill = str(target_after_fill.get("side", "")).upper()
        close_side2 = "SELL" if side_after_fill == "LONG" else "BUY"
        size_after_fill = to_decimal(target_after_fill.get("size", "0"), "size_after_fill")

        depth2 = get_depth(args.match_base, args.symbol)
        b2, a2 = best_bid_ask(depth2, fallback=(best_bid, best_ask))
        close_price = make_pair_price_for_taker(close_side2, b2, a2)
        close_qty_int = max(1, args.close_qty_int)
        close_seed_side = "BUY" if close_side2 == "SELL" else "SELL"
        close_seed_payload = {
            "symbol": args.symbol,
            "side": close_seed_side,
            "type": "LIMIT",
            "price": dec_to_int_str(close_price),
            "quantity": str(close_qty_int),
            "leverage": 10,
            "timeInForce": "GTC",
            "clientOrderId": f"acc-seed-close-{int(time.time() * 1000)}",
        }
        close_seed_oid = submit_order(
            api_gateway=args.api_gateway,
            oms_base=args.oms_base,
            token=maker_token,
            user_id=maker_user_id,
            payload=close_seed_payload,
            idempotency_key=f"idem-{close_seed_payload['clientOrderId']}",
        )
        close_seed_st_init, close_seed_obj_init = wait_order_status(
            args.oms_base,
            maker_user_id,
            args.symbol,
            close_seed_oid,
            ACTIVE_ORDER_STATUSES | {"FILLED", "CANCELED", "REJECTED"},
            15,
        )
        close_seed_filled_init = to_decimal(
            close_seed_obj_init.get("filledQuantity", "0"),
            "closeSeed.filledQuantity",
        )
        if close_seed_st_init in {"REJECTED", "CANCELED"} and close_seed_filled_init <= 0:
            raise SuiteError(
                f"Close seed order failed before close submit, status={close_seed_st_init}, query={close_seed_obj_init}"
            )

        close_payload = {
            "symbol": args.symbol,
            "side": close_side2,
            "type": "LIMIT",
            "price": dec_to_int_str(close_price),
            "quantity": str(close_qty_int),
            "leverage": 10,
            "reduceOnly": True,
            "timeInForce": "GTC",
            "clientOrderId": f"acc-close-{int(time.time() * 1000)}",
        }
        close_submit_mode = "reduceOnly=true"
        try:
            close_oid = submit_order(
                api_gateway=args.api_gateway,
                oms_base=args.oms_base,
                token=token,
                user_id=user_id,
                payload=close_payload,
                idempotency_key=f"idem-{close_payload['clientOrderId']}",
            )
        except Exception:
            # Some OMS versions reject reduceOnly for this route. Retry without it.
            close_submit_mode = "reduceOnly=false_fallback"
            close_payload = dict(close_payload)
            close_payload.pop("reduceOnly", None)
            close_payload["clientOrderId"] = f"acc-close-nr-{int(time.time() * 1000)}"
            close_oid = submit_order(
                api_gateway=args.api_gateway,
                oms_base=args.oms_base,
                token=token,
                user_id=user_id,
                payload=close_payload,
                idempotency_key=f"idem-{close_payload['clientOrderId']}",
            )
        st_close, obj_close = wait_order_status(
            args.oms_base,
            user_id,
            args.symbol,
            close_oid,
            {"FILLED", "PARTIALLY_FILLED", "REJECTED", "CANCELED"},
            args.timeout_sec,
        )
        if st_close in {"REJECTED", "CANCELED"}:
            raise SuiteError(f"Manual close order not FILLED, status={st_close}, query={obj_close}")

        close_seed_st_final, close_seed_obj_final = wait_order_status(
            args.oms_base,
            maker_user_id,
            args.symbol,
            close_seed_oid,
            ACTIVE_ORDER_STATUSES | {"FILLED", "CANCELED", "REJECTED"},
            15,
        )
        if close_seed_st_final in ACTIVE_ORDER_STATUSES:
            cancel_order(args.api_gateway, args.oms_base, maker_token, maker_user_id, close_seed_oid)
            close_seed_st_final, close_seed_obj_final = wait_order_status(
                args.oms_base,
                maker_user_id,
                args.symbol,
                close_seed_oid,
                {"FILLED", "CANCELED", "REJECTED"},
                15,
            )

        # Verify same-side position size decreased (or position closed).
        time.sleep(2)
        target_after_close = wait_active_position(args.api_gateway, token, args.symbol, max(8, min(args.timeout_sec, 20)))
        size_after_close = Decimal("0")
        same_side_exists = target_after_close and str(target_after_close.get("side", "")).upper() == side_after_fill
        if same_side_exists:
            size_after_close = to_decimal(target_after_close.get("size", "0"), "size_after_close")
        reduced = not same_side_exists or size_after_close < size_after_fill
        if not reduced:
            raise SuiteError(
                f"Manual close did not reduce target side position, "
                f"sizeBefore={size_after_fill}, sizeAfter={size_after_close}, positionAfterClose={target_after_close}"
            )

        steps.append(
            StepResult(
                name="manual_close_flow",
                ok=True,
                message="Manual close order filled and position reduced",
                details={
                    "orderId": close_oid,
                    "closeSide": close_side2,
                    "closePrice": str(close_price),
                    "sizeAfterFill": str(size_after_fill),
                    "sizeAfterClose": str(size_after_close),
                    "submitMode": close_submit_mode,
                    "orderStatusObserved": st_close,
                    "seedOrderId": close_seed_oid,
                    "seedOrderSide": close_seed_side,
                    "seedOrderStatus": close_seed_st_final,
                    "seedFilledQuantity": str(
                        to_decimal(close_seed_obj_final.get("filledQuantity", "0"), "closeSeed.filledQuantityFinal")
                    ),
                },
            )
        )
    except Exception as exc:
        steps.append(StepResult(name="manual_close_flow", ok=False, message="Manual close flow failed", details={"error": str(exc)}))

    # Step: liquidation price formula.
    try:
        target_now = wait_active_position(args.api_gateway, token, args.symbol, max(10, min(args.timeout_sec, 20)))
        if not target_now:
            raise SuiteError("No active position for liquidation price check")

        side = str(target_now.get("side", "")).upper()
        entry = to_decimal(target_now.get("entryPrice"), "entryPrice")
        liq = to_decimal(target_now.get("liquidationPrice"), "liquidationPrice")
        leverage = to_decimal(target_now.get("leverage", "10"), "leverage")
        expected = compute_liq_expected(entry=entry, side=side, leverage=leverage)
        diff = abs(liq - expected)
        tolerance = max(Decimal("0.0001"), expected.copy_abs() * Decimal("0.001"))
        if diff > tolerance:
            raise SuiteError(
                f"Liquidation price mismatch, actual={liq}, expected={expected}, diff={diff}, tolerance={tolerance}"
            )
        steps.append(
            StepResult(
                name="liquidation_price_formula",
                ok=True,
                message="Liquidation price formula matched",
                details={
                    "side": side,
                    "entryPrice": str(entry),
                    "leverage": str(leverage),
                    "actualLiquidationPrice": str(liq),
                    "expectedLiquidationPrice": str(expected),
                    "diff": str(diff),
                    "tolerance": str(tolerance),
                },
            )
        )
    except Exception as exc:
        steps.append(
            StepResult(
                name="liquidation_price_formula",
                ok=False,
                message="Liquidation price formula check failed",
                details={"error": str(exc)},
            )
        )

    if not args.skip_pnl_push:
        steps.append(
            run_pnl_push_check(
                symbol=args.symbol,
                username=args.username,
                password=args.password,
                api_gateway=args.api_gateway,
                private_ws=args.private_ws,
                retries=args.pnl_retries,
                kafka_container=args.kafka_container,
                kafka_broker=args.kafka_broker,
                mark_topic=args.mark_topic,
            )
        )

    if args.with_liquidation:
        liq_seed_oid: str | None = None
        liq_seed_side = ""
        liq_seed_price = Decimal("0")
        liq_seed_required_qty = Decimal("0")
        liq_seed_final_status = ""
        liq_seed_filled = Decimal("0")
        try:
            ensure_liquidation_ready(
                args.auto_start,
                args.liquidation_probe_timeout_sec,
                args.liquidation_probe_stable_sec,
                args.liquidation_restart_attempts,
            )

            # Use live active position for symbol.
            target_liq = wait_active_position(args.api_gateway, token, args.symbol, max(10, min(args.timeout_sec, 20)))
            if not target_liq:
                raise SuiteError("No active position for liquidation trigger")

            side_name = str(target_liq.get("side", "")).upper()
            side_code = 1 if side_name == "LONG" else 2
            size_dec = to_decimal(target_liq.get("size"), "position.size")
            entry_dec = to_decimal(target_liq.get("entryPrice"), "position.entryPrice")
            liq_dec = to_decimal(target_liq.get("liquidationPrice"), "position.liquidationPrice")
            margin_ratio_dec = to_decimal(target_liq.get("marginRatio", "0"), "position.marginRatio")
            unrealized_dec = to_decimal(target_liq.get("unrealizedPnl", "0"), "position.unrealizedPnl")

            internal = fetch_internal_position(args.position_internal_base, user_id, args.symbol, side_code)
            if not internal:
                raise SuiteError("Failed to query internal position for liquidation trigger")
            real_position_id = internal.get("id")
            if real_position_id is None:
                raise SuiteError(f"Internal position has no id: {internal}")

            # Build adverse mark beyond liquidation line.
            if side_name == "LONG":
                adverse_mark = (liq_dec * Decimal("0.99")).quantize(Decimal("0.00000001"), rounding=ROUND_HALF_UP)
            else:
                adverse_mark = (liq_dec * Decimal("1.01")).quantize(Decimal("0.00000001"), rounding=ROUND_HALF_UP)

            max_size_int = int((size_dec * SCALE).quantize(Decimal("1"), rounding=ROUND_HALF_UP))
            qty_int = max(1, min(args.liquidation_qty_int, max_size_int))
            liq_taker_side = "SELL" if side_name == "LONG" else "BUY"
            liq_seed_required_qty = int_to_dec(qty_int)

            # Seed opposite liquidity before trigger so liquidation order can be matched deterministically.
            depth_liq = get_depth(args.match_base, args.symbol)
            b_liq, a_liq = best_bid_ask(depth_liq, fallback=(best_bid, best_ask))
            liq_seed_side = "BUY" if liq_taker_side == "SELL" else "SELL"
            liq_seed_price = make_liquidation_seed_price(liq_taker_side, b_liq, a_liq)
            liq_seed_qty_int = qty_int * max(1, args.liquidation_liquidity_multiplier)
            liq_seed_payload = {
                "symbol": args.symbol,
                "side": liq_seed_side,
                "type": "LIMIT",
                "price": dec_to_int_str(liq_seed_price),
                "quantity": str(liq_seed_qty_int),
                "leverage": 10,
                "timeInForce": "GTC",
                "clientOrderId": f"acc-liq-seed-{int(time.time() * 1000)}",
            }
            liq_seed_oid = submit_order(
                api_gateway=args.api_gateway,
                oms_base=args.oms_base,
                token=maker_token,
                user_id=maker_user_id,
                payload=liq_seed_payload,
                idempotency_key=f"idem-{liq_seed_payload['clientOrderId']}",
            )
            liq_seed_st_init, liq_seed_obj_init = wait_order_status(
                args.oms_base,
                maker_user_id,
                args.symbol,
                liq_seed_oid,
                ACTIVE_ORDER_STATUSES | {"FILLED", "CANCELED", "REJECTED"},
                15,
            )
            liq_seed_filled_init = to_decimal(liq_seed_obj_init.get("filledQuantity", "0"), "liqSeed.filledQuantity")
            if liq_seed_st_init in {"REJECTED", "CANCELED"} and liq_seed_filled_init <= 0:
                raise SuiteError(
                    f"Liquidation seed order failed before trigger, status={liq_seed_st_init}, query={liq_seed_obj_init}"
                )
            if liq_seed_st_init == "FILLED":
                raise SuiteError(
                    "Liquidation seed order filled before trigger; cannot guarantee deterministic full-fill path"
                )

            depth_ready = False
            observed_counterparty_qty = Decimal("0")
            depth_deadline = time.time() + 12
            while time.time() < depth_deadline:
                depth_check = get_depth(args.match_base, args.symbol)
                observed_counterparty_qty = depth_counterparty_qty(depth_check, liq_taker_side, liq_seed_price)
                if observed_counterparty_qty >= liq_seed_required_qty:
                    depth_ready = True
                    break
                time.sleep(1)
            if not depth_ready:
                raise SuiteError(
                    "Seeded counterparty depth not enough for single-shot liquidation, "
                    f"required={liq_seed_required_qty}, observed={observed_counterparty_qty}, "
                    f"takerSide={liq_taker_side}, seedPrice={liq_seed_price}"
                )
            liq_seed_final_status = liq_seed_st_init
            liq_seed_filled = liq_seed_filled_init

            event_ts = int(time.time() * 1000)
            liquidation_id = f"ACC_LIQ_{event_ts}_{real_position_id}"
            event = {
                "userId": user_id,
                "positionId": int(real_position_id),
                "symbol": args.symbol,
                "marginMode": "CROSS",
                "triggerType": "MANUAL_TEST",
                "marginRatio": int((margin_ratio_dec * Decimal("10000")).quantize(Decimal("1"), rounding=ROUND_HALF_UP)),
                "liquidationThreshold": 10000,
                "markPrice": int((adverse_mark * SCALE).quantize(Decimal("1"), rounding=ROUND_HALF_UP)),
                "liquidationPrice": int((liq_dec * SCALE).quantize(Decimal("1"), rounding=ROUND_HALF_UP)),
                "bankruptcyPrice": int((liq_dec * SCALE).quantize(Decimal("1"), rounding=ROUND_HALF_UP)),
                "positionSide": side_code,
                "positionQty": qty_int,
                "entryPrice": int((entry_dec * SCALE).quantize(Decimal("1"), rounding=ROUND_HALF_UP)),
                "currentMargin": 100000000,
                "maintenanceMargin": 5000000,
                "unrealizedPnl": int((unrealized_dec * SCALE).quantize(Decimal("1"), rounding=ROUND_HALF_UP)),
                "leverage": 10,
                "priority": 1,
                "timestamp": event_ts,
                "sequence": event_ts,
                "remark": liquidation_id,
            }

            pre_internal_size = to_decimal(internal.get("size", "0"), "internal.size")
            inject_liquidation_trigger(
                symbol=args.symbol,
                event=event,
                kafka_container=args.kafka_container,
                broker=args.kafka_broker,
                topic=args.liquidation_topic,
            )

            reduced = False
            after_size = pre_internal_size
            deadline = time.time() + args.timeout_sec
            while time.time() < deadline:
                current = fetch_internal_position(args.position_internal_base, user_id, args.symbol, side_code)
                if current and current.get("size") is not None:
                    after_size = to_decimal(current.get("size"), "internal.size.after")
                    if after_size < pre_internal_size:
                        reduced = True
                        break
                time.sleep(1)

            if not reduced:
                raise SuiteError(
                    f"Liquidation trigger did not reduce position size within timeout, "
                    f"before={pre_internal_size}, after={after_size}, event={event}"
                )

            if liq_seed_oid:
                liq_seed_final_status, liq_seed_obj_final = wait_order_status(
                    args.oms_base,
                    maker_user_id,
                    args.symbol,
                    liq_seed_oid,
                    ACTIVE_ORDER_STATUSES | {"FILLED", "CANCELED", "REJECTED"},
                    15,
                )
                liq_seed_filled = to_decimal(liq_seed_obj_final.get("filledQuantity", "0"), "liqSeed.filledQuantityFinal")
                if liq_seed_final_status in ACTIVE_ORDER_STATUSES:
                    cancel_order(args.api_gateway, args.oms_base, maker_token, maker_user_id, liq_seed_oid)
                    liq_seed_final_status, liq_seed_obj_final = wait_order_status(
                        args.oms_base,
                        maker_user_id,
                        args.symbol,
                        liq_seed_oid,
                        {"FILLED", "CANCELED", "REJECTED"},
                        15,
                    )
                    liq_seed_filled = to_decimal(
                        liq_seed_obj_final.get("filledQuantity", "0"),
                        "liqSeed.filledQuantityAfterCancel",
                    )

            steps.append(
                StepResult(
                    name="liquidation_trigger_execution",
                    ok=True,
                    message="Liquidation trigger consumed and position reduced",
                    details={
                        "positionId": real_position_id,
                        "beforeSize": str(pre_internal_size),
                        "afterSize": str(after_size),
                        "event": event,
                        "seedOrderId": liq_seed_oid,
                        "seedOrderSide": liq_seed_side,
                        "seedOrderPrice": str(liq_seed_price),
                        "seedRequiredQty": str(liq_seed_required_qty),
                        "seedOrderStatus": liq_seed_final_status,
                        "seedFilledQuantity": str(liq_seed_filled),
                    },
                )
            )
        except Exception as exc:
            if liq_seed_oid:
                try:
                    liq_seed_st_err, _ = wait_order_status(
                        args.oms_base,
                        maker_user_id,
                        args.symbol,
                        liq_seed_oid,
                        ACTIVE_ORDER_STATUSES | {"FILLED", "CANCELED", "REJECTED"},
                        5,
                    )
                    if liq_seed_st_err in ACTIVE_ORDER_STATUSES:
                        cancel_order(args.api_gateway, args.oms_base, maker_token, maker_user_id, liq_seed_oid)
                except Exception:
                    pass
            steps.append(
                StepResult(
                    name="liquidation_trigger_execution",
                    ok=False,
                    message="Liquidation trigger stage failed",
                    details={"error": str(exc)},
                )
            )
            if args.strict_liquidation:
                md, js = write_reports(Path(args.report_dir), steps, started_at)
                log(f"FAIL liquidation_trigger_execution: {exc}")
                log(f"Report: {md}")
                log(f"Details: {js}")
                return 1

    md, js = write_reports(Path(args.report_dir), steps, started_at)
    failed: list[StepResult] = []
    for s in steps:
        if s.ok:
            continue
        if (
            s.name == "liquidation_trigger_execution"
            and args.with_liquidation
            and not args.strict_liquidation
        ):
            continue
        failed.append(s)
    for s in steps:
        log(f"{'PASS' if s.ok else 'FAIL'} {s.name}: {s.message}")
    log(f"Report: {md}")
    log(f"Details: {js}")
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
