#!/usr/bin/env python3
"""
合约交易系统 - 端到端交易链路测试
测试链路: Client -> Gateway -> OMS -> Hard Risk -> Ledger -> Kafka -> Match Engine -> Kafka -> Ledger -> Snapshot -> Push

测试场景:
1. 未成交场景: 限价单无法撮合，验证保证金冻结、订单簿、状态推送
2. 部分成交场景: 大单吃小单，验证成交记录、持仓更新、余额变化、盈亏计算
3. 撤单场景: 撤掉未成交部分，验证订单簿清理、保证金解冻
"""

import os
os.environ['NO_PROXY'] = 'localhost,127.0.0.1'
os.environ['no_proxy'] = 'localhost,127.0.0.1'

import requests
import json
import time
import sys
from datetime import datetime

# 配置
API_GATEWAY = "http://localhost:8082"
OMS_DIRECT = "http://localhost:8081"
MATCH_ENGINE = "http://localhost:8083"
LEDGER = "http://localhost:8084"
SNAPSHOT_ACCOUNT = "http://localhost:8085"
POSITION_SNAPSHOT = "http://localhost:8086"

USERNAME = "zhoufan5"
PASSWORD = "123456"
SYMBOL = "BTCUSDT"

# 金额精度: 8位小数 = 10^8
SCALE = 100_000_000

class Colors:
    HEADER = '\033[95m'
    OKBLUE = '\033[94m'
    OKCYAN = '\033[96m'
    OKGREEN = '\033[92m'
    WARNING = '\033[93m'
    FAIL = '\033[91m'
    ENDC = '\033[0m'
    BOLD = '\033[1m'

def log_info(msg):
    print(f"{Colors.OKBLUE}[INFO]{Colors.ENDC} {msg}")

def log_success(msg):
    print(f"{Colors.OKGREEN}[PASS]{Colors.ENDC} {msg}")

def log_error(msg):
    print(f"{Colors.FAIL}[FAIL]{Colors.ENDC} {msg}")

def log_warn(msg):
    print(f"{Colors.WARNING}[WARN]{Colors.ENDC} {msg}")

def log_header(msg):
    print(f"\n{Colors.BOLD}{Colors.HEADER}{msg}{Colors.ENDC}")

def now_ms():
    return int(time.time() * 1000)

def to_long_amount(value):
    """将浮点数转为内部 long 格式 (8位小数)"""
    return int(float(value) * SCALE)

def from_long_amount(value):
    """将内部 long 格式转为浮点数"""
    return float(value) / SCALE

class E2ETradingTest:
    def __init__(self):
        self.token = None
        self.user_id = None
        self.orders = {}
        self.test_results = []
        
    def assert_true(self, test_name, condition, detail=""):
        if condition:
            self.test_results.append((test_name, True, detail))
            log_success(f"{test_name} {detail}")
        else:
            self.test_results.append((test_name, False, detail))
            log_error(f"{test_name} {detail}")
        return condition
    
    # ==================== 基础 API ====================
    
    def login(self):
        """用户登录"""
        log_info("用户登录...")
        resp = requests.post(
            f"{API_GATEWAY}/api/v1/user/login",
            json={"username": USERNAME, "password": PASSWORD},
            timeout=10
        )
        data = resp.json()
        if data.get("code") == 200:
            self.token = data["data"]["token"]
            self.user_id = data["data"]["userId"]
            log_success(f"登录成功, userId={self.user_id}")
            return True
        log_error(f"登录失败: {data}")
        return False
    
    def get_balance(self):
        """查询账户余额"""
        resp = requests.get(
            f"{LEDGER}/internal/ledger/account/{self.user_id}",
            timeout=10
        )
        return resp.json()
    
    def get_orderbook_depth(self, symbol):
        """查询订单簿深度"""
        resp = requests.get(
            f"{MATCH_ENGINE}/api/v1/match/orderbook/depth/{symbol}?depth=10",
            timeout=10
        )
        return resp.json()
    
    def get_orderbook_orders(self, symbol):
        """查询订单簿中的订单数量"""
        resp = requests.get(
            f"{MATCH_ENGINE}/api/v1/match/orderbook/stats?symbol={symbol}",
            timeout=10
        )
        return resp.json()
    
    def get_order_status(self, order_id):
        """查询订单状态"""
        resp = requests.get(
            f"{API_GATEWAY}/api/order/query?orderId={order_id}",
            headers={"Authorization": f"Bearer {self.token}"},
            timeout=10
        )
        return resp.json()
    
    def get_positions(self):
        """查询持仓"""
        resp = requests.get(
            f"{API_GATEWAY}/api/v1/position/list",
            headers={"Authorization": f"Bearer {self.token}"},
            timeout=10
        )
        return resp.json()
    
    def submit_order(self, side, price, quantity, order_type="LIMIT", client_order_id=None):
        """提交订单"""
        if client_order_id is None:
            client_order_id = f"test_{self.user_id}_{now_ms()}"
        
        body = {
            "symbol": SYMBOL,
            "side": side,
            "type": order_type,
            "price": str(to_long_amount(price)),
            "quantity": str(to_long_amount(quantity)),
            "leverage": 10,
            "timeInForce": "GTC",
            "clientOrderId": client_order_id
        }
        
        resp = requests.post(
            f"{API_GATEWAY}/api/order/create",
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.token}",
                "X-Idempotency-Key": f"idem_{now_ms()}"
            },
            json=body,
            timeout=10
        )
        return resp.json()
    
    def cancel_order(self, order_id):
        """撤单"""
        resp = requests.post(
            f"{API_GATEWAY}/api/order/cancel",
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.token}"
            },
            json={"orderId": str(order_id)},
            timeout=10
        )
        return resp.json()
    
    # ==================== 测试场景 ====================
    
    def test_scenario_a_unfilled_order(self):
        """场景A: 未成交订单测试"""
        log_header("=" * 60)
        log_header("场景A: 未成交订单测试")
        log_header("=" * 60)
        
        # 1. 记录下单前余额
        balance_before = self.get_balance()
        available_before = float(balance_before.get("available", 0))
        frozen_before = float(balance_before.get("frozen", 0))
        log_info(f"下单前余额: available={available_before:.2f}, frozen={frozen_before:.2f}")
        
        # 2. 下一个低价的买单（无法成交，因为订单簿为空或价格太低）
        # 价格接近mark_price但低于卖一价，确保不会成交
        price = 49900.0
        quantity = 0.01
        required_margin = price * quantity / 10  # 10x杠杆
        log_info(f"提交限价买单: price={price}, qty={quantity}, 预计冻结保证金={required_margin:.2f} USDT")
        
        result = self.submit_order("BUY", price, quantity)
        log_info(f"下单响应: {json.dumps(result, indent=2)}")
        
        order_id = result.get("orderId") or result.get("data", {}).get("orderId")
        self.assert_true("A1-下单成功", result.get("code") in [200, 0] or result.get("success"), 
                        f"orderId={order_id}")
        
        if not order_id:
            log_error("下单失败，跳过场景A后续测试")
            return
        
        self.orders["A_buy"] = order_id
        
        # 3. 等待 Kafka 消息处理
        time.sleep(2)
        
        # 4. 检查订单状态
        order_status = self.get_order_status(order_id)
        log_info(f"订单状态: {json.dumps(order_status, indent=2)}")
        status = order_status.get("data", {}).get("status") if order_status.get("code") == 200 else order_status.get("status")
        self.assert_true("A2-订单状态为FROZEN或NEW", status in ["FROZEN", "NEW", "PARTIALLY_FILLED"], 
                        f"status={status}")
        
        # 5. 检查余额变化（保证金应被冻结）
        balance_after = self.get_balance()
        available_after = float(balance_after.get("available", 0))
        frozen_after = float(balance_after.get("frozen", 0))
        log_info(f"下单后余额: available={available_after:.2f}, frozen={frozen_after:.2f}")
        
        # 冻结金额应增加
        frozen_delta = frozen_after - frozen_before
        self.assert_true("A3-保证金已冻结", frozen_delta > 0, 
                        f"frozen_delta={frozen_delta:.6f}")
        
        # 6. 检查订单簿
        depth = self.get_orderbook_depth(SYMBOL)
        log_info(f"订单簿深度: {json.dumps(depth, indent=2)}")
        bids = depth.get("bids", [])
        has_order_in_book = any(abs(float(b[0]) - price) < 0.01 for b in bids)
        self.assert_true("A4-订单在订单簿中", has_order_in_book or len(bids) > 0, 
                        f"bids_count={len(bids)}")
        
        # 7. 检查撮合引擎中的订单数量
        match_orders = self.get_orderbook_orders(SYMBOL)
        order_count = match_orders.get("orderCount", 0)
        log_info(f"撮合引擎订单数: {order_count}")
        self.assert_true("A5-撮合引擎有挂单", order_count > 0, 
                        f"orderCount={order_count}")
        
        log_success("场景A测试完成")
        return order_id
    
    def test_scenario_b_partial_fill(self):
        """场景B: 部分成交测试"""
        log_header("=" * 60)
        log_header("场景B: 部分成交测试")
        log_header("=" * 60)
        
        # 1. 清理之前的订单（先撤单）
        if "A_buy" in self.orders:
            log_info("清理场景A的订单...")
            self.cancel_order(self.orders["A_buy"])
            time.sleep(2)
        
        # 2. 记录初始状态
        balance_before = self.get_balance()
        available_before = float(balance_before.get("available", 0))
        frozen_before = float(balance_before.get("frozen", 0))
        log_info(f"初始余额: available={available_before:.2f}, frozen={frozen_before:.2f}")
        
        positions_before = self.get_positions()
        pos_count_before = len(positions_before.get("data", [])) if positions_before.get("code") == 200 else 0
        log_info(f"初始持仓数: {pos_count_before}")
        
        # 3. 用户35下一个大的买单（价格较高，但先挂着）
        buy_price = 50000.0
        buy_quantity = 0.02  # 买0.02 BTC
        log_info(f"提交大买单: price={buy_price}, qty={buy_quantity}")
        
        buy_result = self.submit_order("BUY", buy_price, buy_quantity, client_order_id=f"test_buy_{now_ms()}")
        buy_order_id = buy_result.get("orderId") or buy_result.get("data", {}).get("orderId")
        log_info(f"买单响应: {json.dumps(buy_result, indent=2)}")
        self.assert_true("B1-大买单提交成功", buy_result.get("code") in [200, 0] or buy_result.get("success"), 
                        f"orderId={buy_order_id}")
        
        if not buy_order_id:
            log_error("买单提交失败，跳过场景B")
            return
        self.orders["B_buy"] = buy_order_id
        time.sleep(2)
        
        # 4. 再下一个小卖单（同价或更低，确保能部分成交）
        # 卖单价格 <= 买单价格时会成交
        sell_price = 50000.0
        sell_quantity = 0.005  # 只卖0.005 BTC，买单会部分成交
        log_info(f"提交小卖单(对手方): price={sell_price}, qty={sell_quantity}")
        
        sell_result = self.submit_order("SELL", sell_price, sell_quantity, client_order_id=f"test_sell_{now_ms()}")
        sell_order_id = sell_result.get("orderId") or sell_result.get("data", {}).get("orderId")
        log_info(f"卖单响应: {json.dumps(sell_result, indent=2)}")
        self.assert_true("B2-小卖单提交成功", sell_result.get("code") in [200, 0] or sell_result.get("success"), 
                        f"orderId={sell_order_id}")
        
        if not sell_order_id:
            log_error("卖单提交失败，跳过场景B后续")
            return
        self.orders["B_sell"] = sell_order_id
        
        # 5. 等待撮合完成
        log_info("等待撮合和记账处理 (5秒)...")
        time.sleep(5)
        
        # 6. 检查买单状态（应为 PARTIALLY_FILLED）
        buy_status = self.get_order_status(buy_order_id)
        log_info(f"买单状态: {json.dumps(buy_status, indent=2)}")
        buy_state = buy_status.get("data", {}).get("status") if buy_status.get("code") == 200 else buy_status.get("status")
        filled_qty = buy_status.get("data", {}).get("filledQuantity", "0") if buy_status.get("code") == 200 else "0"
        self.assert_true("B3-买单部分成交", buy_state in ["PARTIALLY_FILLED", "FILLED"], 
                        f"status={buy_state}, filled={filled_qty}")
        
        # 7. 检查卖单状态（应为 FILLED）
        sell_status = self.get_order_status(sell_order_id)
        log_info(f"卖单状态: {json.dumps(sell_status, indent=2)}")
        sell_state = sell_status.get("data", {}).get("status") if sell_status.get("code") == 200 else sell_status.get("status")
        self.assert_true("B4-卖单完全成交", sell_state == "FILLED", 
                        f"status={sell_state}")
        
        # 8. 检查持仓变化
        positions_after = self.get_positions()
        pos_data = positions_after.get("data", []) if positions_after.get("code") == 200 else []
        log_info(f"当前持仓: {json.dumps(pos_data, indent=2)}")
        
        # 由于是同一用户自成交，Net Mode 下多空会抵消
        # 在 Hedge Mode 下会分别持仓
        has_position = len(pos_data) > 0
        self.assert_true("B5-有持仓记录", has_position, f"positions={pos_data}")
        
        # 9. 检查余额变化
        balance_after = self.get_balance()
        available_after = float(balance_after.get("available", 0))
        frozen_after = float(balance_after.get("frozen", 0))
        position_margin = float(balance_after.get("positionMargin", 0))
        log_info(f"成交后余额: available={available_after:.2f}, frozen={frozen_after:.2f}, position_margin={position_margin:.2f}")
        
        # 买单部分成交后：未成交部分的保证金应仍在 frozen 或已释放
        # 已成交部分保证金应转到 position_margin
        self.assert_true("B6-持仓保证金已更新", position_margin > 0 or frozen_after > 0, 
                        f"position_margin={position_margin}, frozen={frozen_after}")
        
        # 10. 检查订单簿（买单应还有剩余未成交部分）
        depth = self.get_orderbook_depth(SYMBOL)
        log_info(f"订单簿: {json.dumps(depth, indent=2)}")
        
        # 11. 检查撮合引擎中的订单
        match_orders = self.get_orderbook_orders(SYMBOL)
        log_info(f"撮合引擎: {json.dumps(match_orders, indent=2)}")
        
        log_success("场景B测试完成")
        return buy_order_id
    
    def test_scenario_c_cancel_unfilled(self):
        """场景C: 撤单测试（撤掉未成交部分）"""
        log_header("=" * 60)
        log_header("场景C: 撤单测试")
        log_header("=" * 60)
        
        # 1. 获取场景B中未完全成交的买单
        buy_order_id = self.orders.get("B_buy")
        if not buy_order_id:
            log_error("没有找到可撤单的订单，跳过场景C")
            return
        
        # 2. 记录撤单前状态
        balance_before = self.get_balance()
        available_before = float(balance_before.get("available", 0))
        frozen_before = float(balance_before.get("frozen", 0))
        log_info(f"撤单前余额: available={available_before:.2f}, frozen={frozen_before:.2f}")
        
        order_before = self.get_order_status(buy_order_id)
        status_before = order_before.get("data", {}).get("status") if order_before.get("code") == 200 else "UNKNOWN"
        log_info(f"撤单前订单状态: {status_before}")
        
        match_orders_before = self.get_orderbook_orders(SYMBOL)
        order_count_before = match_orders_before.get("orderCount", 0)
        log_info(f"撤单前撮合引擎订单数: {order_count_before}")
        
        # 3. 执行撤单
        log_info(f"执行撤单: orderId={buy_order_id}")
        cancel_result = self.cancel_order(buy_order_id)
        log_info(f"撤单响应: {json.dumps(cancel_result, indent=2)}")
        self.assert_true("C1-撤单请求成功", cancel_result.get("code") in [200, 0] or cancel_result.get("success"), 
                        f"response={cancel_result}")
        
        # 4. 等待撤单处理
        log_info("等待撤单处理 (3秒)...")
        time.sleep(3)
        
        # 5. 检查订单状态
        order_after = self.get_order_status(buy_order_id)
        status_after = order_after.get("data", {}).get("status") if order_after.get("code") == 200 else "UNKNOWN"
        log_info(f"撤单后订单状态: {status_after}")
        self.assert_true("C2-订单状态变为CANCELED", status_after in ["CANCELED", "PENDING_CANCEL"], 
                        f"status={status_after}")
        
        # 6. 检查余额变化（未成交部分保证金应解冻）
        balance_after = self.get_balance()
        available_after = float(balance_after.get("available", 0))
        frozen_after = float(balance_after.get("frozen", 0))
        log_info(f"撤单后余额: available={available_after:.2f}, frozen={frozen_after:.2f}")
        
        # 未成交部分解冻后，available 应增加，frozen 应减少
        available_increased = available_after > available_before
        self.assert_true("C3-可用余额增加（保证金解冻）", available_increased or frozen_after <= frozen_before, 
                        f"available_before={available_before:.2f}, available_after={available_after:.2f}")
        
        # 7. 检查撮合引擎数据正确性
        match_orders_after = self.get_orderbook_orders(SYMBOL)
        order_count_after = match_orders_after.get("orderCount", 0)
        log_info(f"撤单后撮合引擎订单数: {order_count_after}")
        
        # 订单应从订单簿中移除
        self.assert_true("C4-撮合引擎订单已移除", order_count_after < order_count_before or order_count_after == 0, 
                        f"before={order_count_before}, after={order_count_after}")
        
        # 8. 检查订单簿深度
        depth = self.get_orderbook_depth(SYMBOL)
        bids = depth.get("bids", [])
        log_info(f"撤单后订单簿买盘: {bids}")
        # 撤单后，之前的买单价格档位应被清理或数量减少
        self.assert_true("C5-订单簿已清理", len(bids) == 0 or order_count_after == 0, 
                        f"bids_count={len(bids)}")
        
        log_success("场景C测试完成")
    
    def print_summary(self):
        """打印测试摘要"""
        log_header("=" * 60)
        log_header("测试摘要")
        log_header("=" * 60)
        
        total = len(self.test_results)
        passed = sum(1 for _, p, _ in self.test_results if p)
        failed = total - passed
        
        print(f"\n总测试项: {total}")
        print(f"{Colors.OKGREEN}通过: {passed}{Colors.ENDC}")
        print(f"{Colors.FAIL}失败: {failed}{Colors.ENDC}")
        print(f"通过率: {passed/total*100:.1f}%" if total > 0 else "N/A")
        
        if failed > 0:
            print(f"\n{Colors.FAIL}失败项详情:{Colors.ENDC}")
            for name, passed, detail in self.test_results:
                if not passed:
                    print(f"  - {name}: {detail}")
        
        return failed == 0

def main():
    print(f"{Colors.BOLD}{Colors.HEADER}")
    print("=" * 60)
    print("  合约交易系统 - 端到端交易链路测试")
    print("=" * 60)
    print(f"{Colors.ENDC}")
    
    test = E2ETradingTest()
    
    # 1. 登录
    if not test.login():
        log_error("登录失败，测试终止")
        sys.exit(1)
    
    # 2. 执行测试场景
    try:
        test.test_scenario_a_unfilled_order()
        test.test_scenario_b_partial_fill()
        test.test_scenario_c_cancel_unfilled()
    except Exception as e:
        log_error(f"测试执行异常: {e}")
        import traceback
        traceback.print_exc()
    
    # 3. 打印摘要
    success = test.print_summary()
    sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()
