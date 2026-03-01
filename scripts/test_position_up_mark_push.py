#!/usr/bin/env python3
"""
E2E self-check for position PnL push:
1) login
2) query current positions
3) pick one active position
4) inject one mark-price event into Kafka topic mark-price-update
5) subscribe private WS "position"
6) assert position.up changes
"""

from __future__ import annotations

import asyncio
import json
import os
import ssl
import subprocess
import sys
import time
from contextlib import contextmanager
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from typing import Any
from urllib.parse import quote, urlparse
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

try:
    import websockets
except ModuleNotFoundError as exc:
    print("Missing dependency: websockets. Install with `pip install websockets`.", file=sys.stderr)
    raise SystemExit(2) from exc


API_GATEWAY = os.getenv("API_GATEWAY", "http://127.0.0.1:8082")
PRIVATE_WS = os.getenv("PRIVATE_WS", "ws://127.0.0.1:8097/ws/private")
USERNAME = os.getenv("USERNAME", "zhoufan6")
PASSWORD = os.getenv("PASSWORD", "123456")
TOKEN_OVERRIDE = os.getenv("TOKEN", "").strip()
USER_ID_OVERRIDE = os.getenv("USER_ID", "").strip()
LOGIN_URL = os.getenv("LOGIN_URL", f"{API_GATEWAY}/api/v1/user/login")
POSITION_URL = os.getenv("POSITION_URL", f"{API_GATEWAY}/api/v1/position/list")
TARGET_SYMBOL = os.getenv("SYMBOL", "").strip().upper()
KAFKA_CONTAINER = os.getenv("KAFKA_CONTAINER", "kafka-1")
KAFKA_BROKER = os.getenv("KAFKA_BROKER", "localhost:9092")
MARK_TOPIC = os.getenv("MARK_TOPIC", "mark-price-update")
WS_TIMEOUT_SEC = int(os.getenv("WS_TIMEOUT_SEC", "20"))
PRICE_MOVE_BPS = int(os.getenv("PRICE_MOVE_BPS", "200"))  # 200 bps = 2%
VERIFY_TLS = os.getenv("VERIFY_TLS", "false").lower() in {"1", "true", "yes"}
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


@dataclass
class PositionSnapshot:
    symbol: str
    side: str
    size: Decimal
    entry_price: Decimal
    unrealized_pnl: Decimal


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}")


def is_local_host(url: str) -> bool:
    host = (urlparse(url).hostname or "").lower()
    return host in {"127.0.0.1", "localhost", "::1"}


@contextmanager
def bypass_proxy_for_local_ws(url: str):
    if not is_local_host(url):
        yield
        return

    saved: dict[str, str] = {}
    for key in PROXY_ENV_KEYS:
        if key in os.environ:
            saved[key] = os.environ.pop(key)

    # Ensure localhost never goes through an outbound proxy.
    os.environ["NO_PROXY"] = "localhost,127.0.0.1,::1"
    os.environ["no_proxy"] = "localhost,127.0.0.1,::1"
    try:
        yield
    finally:
        os.environ.pop("NO_PROXY", None)
        os.environ.pop("no_proxy", None)
        os.environ.update(saved)


def to_decimal(raw: Any, field: str) -> Decimal:
    if raw is None:
        raise ValueError(f"Missing required field: {field}")
    try:
        return Decimal(str(raw))
    except (InvalidOperation, TypeError) as exc:
        raise ValueError(f"Invalid decimal for {field}: {raw}") from exc


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

    request = Request(url=url, data=body, method=method, headers=req_headers)

    ssl_ctx = None
    if url.startswith("https") and not VERIFY_TLS:
        ssl_ctx = ssl._create_unverified_context()

    try:
        with urlopen(request, timeout=timeout, context=ssl_ctx) as resp:
            raw = resp.read().decode("utf-8")
            status = int(resp.getcode())
    except HTTPError as exc:
        body_text = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code} for {method} {url}: {body_text}") from exc
    except URLError as exc:
        raise RuntimeError(f"HTTP request failed for {method} {url}: {exc}") from exc

    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"Non-JSON response from {method} {url}: {raw[:200]}") from exc

    return status, parsed


def login() -> tuple[str, int | None]:
    _, payload = http_json(
        method="POST",
        url=LOGIN_URL,
        payload={"username": USERNAME, "password": PASSWORD},
        timeout=10,
    )
    data = payload.get("data", payload) if isinstance(payload, dict) else {}

    code = payload.get("code") if isinstance(payload, dict) else None
    success = payload.get("success") if isinstance(payload, dict) else None
    token = data.get("token") or data.get("accessToken")
    user_id = data.get("userId") or data.get("id")

    if not token:
        raise RuntimeError(f"Login failed, no token in response: {payload}")
    if code not in (None, 0, 200, "0", "200") and success not in (None, True):
        raise RuntimeError(f"Login failed, response={payload}")

    uid = int(user_id) if user_id is not None else None
    return token, uid


def parse_positions(response_json: Any) -> list[dict[str, Any]]:
    if not isinstance(response_json, dict):
        return []

    data = response_json.get("data")
    if isinstance(data, dict) and isinstance(data.get("positions"), list):
        return data["positions"]
    if isinstance(response_json.get("positions"), list):
        return response_json["positions"]
    return []


def get_active_position(token: str, user_id: int | None) -> PositionSnapshot:
    headers: dict[str, str] = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if user_id is not None:
        headers["X-User-Id"] = str(user_id)

    status, body = http_json(
        method="GET",
        url=POSITION_URL,
        headers=headers,
        timeout=10,
    )
    if status != 200:
        raise RuntimeError(f"Query positions failed: status={status}, body={body}")

    positions = parse_positions(body)
    active: list[PositionSnapshot] = []

    for item in positions:
        try:
            symbol = str(item.get("symbol", "")).upper()
            side = str(item.get("side", "")).upper()
            size = to_decimal(item.get("size"), "size")
            entry_price = to_decimal(item.get("entryPrice"), "entryPrice")
            up = to_decimal(item.get("unrealizedPnl", "0"), "unrealizedPnl")
        except ValueError:
            continue

        if size <= 0:
            continue
        if TARGET_SYMBOL and symbol != TARGET_SYMBOL:
            continue

        if side not in {"LONG", "SHORT"}:
            position_side = str(item.get("positionSide", "")).strip()
            side = "LONG" if position_side == "1" else "SHORT" if position_side == "2" else "LONG"

        active.append(
            PositionSnapshot(
                symbol=symbol,
                side=side,
                size=size,
                entry_price=entry_price,
                unrealized_pnl=up,
            )
        )

    if not active:
        symbol_info = TARGET_SYMBOL if TARGET_SYMBOL else "(any symbol)"
        raise RuntimeError(f"No active position found for {symbol_info}. Create a position first, then retry.")

    return active[0]


def choose_target_mark(position: PositionSnapshot) -> Decimal:
    move_ratio = Decimal(PRICE_MOVE_BPS) / Decimal(10000)

    # Derive current mark price from unrealized PnL to avoid "already at target" false positives.
    if position.size > 0:
        pnl_per_unit = position.unrealized_pnl / position.size
    else:
        pnl_per_unit = Decimal("0")

    if position.side == "SHORT":
        current_mark = position.entry_price - pnl_per_unit
        target = current_mark * (Decimal("1") - move_ratio)
    else:
        current_mark = position.entry_price + pnl_per_unit
        target = current_mark * (Decimal("1") + move_ratio)

    if target <= 0:
        target = current_mark + Decimal("1")

    target = target.quantize(Decimal("0.00000001"), rounding=ROUND_HALF_UP)
    if target == current_mark:
        target = (current_mark + Decimal("0.01000000")).quantize(
            Decimal("0.00000001"), rounding=ROUND_HALF_UP
        )
    return target


def publish_kafka_keyed_event(
    kafka_container: str,
    broker: str,
    topic: str,
    key: str,
    event: dict[str, Any],
    timeout_sec: int = 20,
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
    proc = subprocess.run(
        cmd,
        text=True,
        capture_output=True,
        timeout=timeout_sec,
        check=False,
    )
    if proc.returncode != 0:
        stderr = proc.stderr.strip()
        raise RuntimeError(
            f"Inject kafka message failed (exit={proc.returncode}). "
            f"container={kafka_container}, topic={topic}, err={stderr}"
        )


def publish_mark_price(symbol: str, mark_price: Decimal) -> dict[str, Any]:
    now_ms = int(time.time() * 1000)
    event = {
        "eventType": "MARK_PRICE_UPDATE",
        "eventTime": now_ms,
        "data": {
            "markPriceId": f"manual-mark-{now_ms}",
            "symbol": symbol,
            "markPrice": format(mark_price, "f"),
            "indexPrice": format(mark_price, "f"),
            "timestamp": now_ms,
        },
    }

    publish_kafka_keyed_event(
        kafka_container=KAFKA_CONTAINER,
        broker=KAFKA_BROKER,
        topic=MARK_TOPIC,
        key=symbol,
        event=event,
        timeout_sec=20,
    )

    return event


async def assert_position_up_changes(
    token: str,
    position: PositionSnapshot,
    target_mark: Decimal,
) -> dict[str, Any]:
    ws_url = f"{PRIVATE_WS}?token={quote(token)}"
    baseline_up = position.unrealized_pnl
    baseline_up_str = format(baseline_up, "f")

    with bypass_proxy_for_local_ws(ws_url):
        async with websockets.connect(ws_url, ping_interval=20, ping_timeout=20) as ws:
            raw = await asyncio.wait_for(ws.recv(), timeout=8)
            hello = json.loads(raw)
            if hello.get("e") != "connectionAck":
                raise RuntimeError(f"Missing connectionAck, got={hello}")

            subscribe = {"method": "SUBSCRIBE", "params": ["position"], "id": 1}
            await ws.send(json.dumps(subscribe))

            # Wait for subscribe ack if it arrives.
            ack_deadline = time.time() + 5
            while time.time() < ack_deadline:
                try:
                    raw_msg = await asyncio.wait_for(ws.recv(), timeout=max(0.2, ack_deadline - time.time()))
                    msg = json.loads(raw_msg)
                    if msg.get("id") == 1 and "result" in msg:
                        break
                except asyncio.TimeoutError:
                    break

            event = publish_mark_price(position.symbol, target_mark)
            log(f"Injected mark event id={event['data']['markPriceId']}, mp={event['data']['markPrice']}")

            deadline = time.time() + WS_TIMEOUT_SEC
            last_same_mp: dict[str, Any] | None = None

            while time.time() < deadline:
                raw_msg = await asyncio.wait_for(ws.recv(), timeout=max(0.2, deadline - time.time()))
                msg = json.loads(raw_msg)

                if msg.get("stream") != "position":
                    continue

                data = msg.get("data") or {}
                if str(data.get("s", "")).upper() != position.symbol:
                    continue

                up_raw = data.get("up")
                mp_raw = data.get("mp")
                if up_raw is None or mp_raw is None:
                    continue

                current_up = to_decimal(up_raw, "position.up")
                current_mp = to_decimal(mp_raw, "position.mp")

                if current_mp == target_mark:
                    if current_up != baseline_up:
                        return {
                            "baselineUp": baseline_up_str,
                            "currentUp": format(current_up, "f"),
                            "markPrice": format(current_mp, "f"),
                            "eventTime": data.get("E"),
                        }
                    last_same_mp = {
                        "baselineUp": baseline_up_str,
                        "currentUp": format(current_up, "f"),
                        "markPrice": format(current_mp, "f"),
                    }

            if last_same_mp is not None:
                raise RuntimeError(
                    "Received position event with injected mark price but up did not change: "
                    f"{last_same_mp}"
                )
            raise RuntimeError("Did not receive position stream event for injected mark price within timeout")


def main() -> int:
    if TOKEN_OVERRIDE:
        token = TOKEN_OVERRIDE
        user_id = int(USER_ID_OVERRIDE) if USER_ID_OVERRIDE else None
        log(f"Using TOKEN from environment, userId={user_id if user_id is not None else 'unknown'}")
    else:
        log("Login")
        token, user_id = login()
        log(f"Login ok, userId={user_id if user_id is not None else 'unknown'}")

    position = get_active_position(token, user_id)
    log(
        "Selected position: "
        f"symbol={position.symbol}, side={position.side}, size={position.size}, "
        f"entry={position.entry_price}, up={position.unrealized_pnl}"
    )

    target_mark = choose_target_mark(position)
    log(f"Target mark price={target_mark} (PRICE_MOVE_BPS={PRICE_MOVE_BPS})")

    result = asyncio.run(assert_position_up_changes(token, position, target_mark))
    log("PASS: position.up changed after mark price injection")
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.TimeoutExpired as exc:
        print(f"Kafka inject timeout: {exc}", file=sys.stderr)
        raise SystemExit(1)
    except Exception as exc:  # pragma: no cover - CLI script
        print(f"FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)
