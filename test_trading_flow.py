#!/usr/bin/env python3
"""
合约交易系统 - 交易流程自动化测试
测试内容：
1. 用户登录
2. 下单（买入/卖出）
3. 订单成交
4. 持仓检查
5. 订单状态变更
"""

import requests
import websocket
import json
import time
import threading
from datetime import datetime

# 配置
API_GATEWAY = "http://localhost:8082"
PRIVATE_PUSH_WS = "ws://localhost:8097/ws/private"
PUBLIC_PUSH_WS = "ws://localhost:8096/ws/market"

# 测试账号
USERNAME = "zhoufan5"
PASSWORD = "123456"

# 全局状态
class TestState:
    def __init__(self):
        self.token = None
        self.user_id = None
        self.orders = {}  # orderId -> order info
        self.positions = {}  # symbol -> position info
        self.ws_messages = []
        self.order_updates = []
        self.position_updates = []
        self.test_results = []
        
state = TestState()

def log(level, message):
    """打印日志"""
    timestamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
    colors = {
        "INFO": "\033[94m",      # 蓝色
        "SUCCESS": "\033[92m",   # 绿色
        "ERROR": "\033[91m",     # 红色
        "WARN": "\033[93m",      # 黄色
        "TEST": "\033[95m",      # 紫色
        "END": "\033[0m"         # 结束
    }
    color = colors.get(level, "")
    print(f"{color}[{timestamp}] [{level}] {message}{colors['END']}")

def add_result(test_name, passed, details=""):
    """添加测试结果"""
    state.test_results.append({
        "name": test_name,
        "passed": passed,
        "details": details,
        "time": datetime.now().strftime("%H:%M:%S")
    })
    status = "✅ 通过" if passed else "❌ 失败"
    log("TEST", f"{test_name}: {status} {details}")

# ==================== API 调用 ====================

def login():
    """用户登录"""
    log("INFO", f"开始登录: {USERNAME}")
    try:
        response = requests.post(
            f"{API_GATEWAY}/api/v1/user/login",
            json={"username": USERNAME, "password": PASSWORD},
            timeout=10
        )
        data = response.json()
        
        if data.get("code") in [0, 200] or data.get("success"):
            user_data = data.get("data", data)
            state.token = user_data.get("token") or user_data.get("accessToken")
            state.user_id = user_data.get("userId") or user_data.get("id")
            log("SUCCESS", f"登录成功! userId={state.user_id}")
            return True
        else:
            log("ERROR", f"登录失败: {data.get('message')}")
            return False
    except Exception as e:
        log("ERROR", f"登录异常: {e}")
        return False

def get_balance():
    """查询账户余额"""
    try:
        response = requests.get(
            f"{API_GATEWAY}/api/v1/account/balance/{state.user_id}",
            headers={"Authorization": f"Bearer {state.token}"},
            timeout=10
        )
        if response.ok:
            data = response.json()
            balance_data = data.get("data", data)
            available = float(balance_data.get("available", 0))
            frozen = float(balance_data.get("frozen", 0))
            log("INFO", f"账户余额 - 可用: {available:.2f} USDT, 冻结: {frozen:.2f} USDT")
            return balance_data
        else:
            log("WARN", f"查询余额失败: {response.status_code}")
            return None
    except Exception as e:
        log("ERROR", f"查询余额异常: {e}")
        return None

def submit_order(symbol, side, order_type, price, quantity, leverage=10):
    """提交订单"""
    log("INFO", f"提交订单: {side} {symbol} {order_type} 价格={price} 数量={quantity} 杠杆={leverage}x")
    
    # 转换为内部精度 (8位小数)
    price_int = int(price * 100000000) if order_type == "LIMIT" else 0
    quantity_int = int(quantity * 100000000)
    
    request_body = {
        "symbol": symbol,
        "side": side,
        "type": order_type,
        "price": str(price_int),
        "quantity": str(quantity_int),
        "leverage": leverage,
        "timeInForce": "GTC",
        "clientOrderId": f"test_{int(time.time() * 1000)}"
    }
    
    try:
        response = requests.post(
            f"{API_GATEWAY}/api/order/create",
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {state.token}",
                "X-Idempotency-Key": f"idem_{int(time.time() * 1000)}"
            },
            json=request_body,
            timeout=10
        )
        
        data = response.json()
        if data.get("success") or data.get("code") in [0, 200]:
            order_id = data.get("orderId") or data.get("data", {}).get("orderId")
            log("SUCCESS", f"订单提交成功! orderId={order_id}")
            state.orders[order_id] = {
                "orderId": order_id,
                "symbol": symbol,
                "side": side,
                "type": order_type,
                "price": price,
                "quantity": quantity,
                "status": "NEW",
                "filledQuantity": 0
            }
            return order_id
        else:
            log("ERROR", f"订单提交失败: {data.get('message') or data.get('errorMsg')}")
            return None
    except Exception as e:
        log("ERROR", f"下单异常: {e}")
        return None

def get_orders(status_filter="active"):
    """查询订单列表"""
    try:
        url = f"{API_GATEWAY}/api/v1/oms/order/list?limit=50"
        if status_filter == "active":
            url += "&status=NEW,PENDING_RISK,FROZEN,PARTIALLY_FILLED"
        else:
            url += "&status=FILLED,CANCELED,REJECTED"
            
        response = requests.get(
            url,
            headers={"Authorization": f"Bearer {state.token}"},
            timeout=10
        )
        
        if response.ok:
            data = response.json()
            orders = data.get("orders", [])
            return orders
        else:
            log("WARN", f"查询订单失败: {response.status_code}")
            return []
    except Exception as e:
        log("ERROR", f"查询订单异常: {e}")
        return []

def get_positions():
    """查询持仓"""
    try:
        # 持仓查询通过 JWT Token 认证，userId 由 Gateway 从 Token 解析透传
        response = requests.get(
            f"{API_GATEWAY}/api/v1/position/list",
            headers={"Authorization": f"Bearer {state.token}"},
            timeout=10
        )
        
        if response.ok:
            data = response.json()
            positions = data.get("positions", [])
            total_pnl = data.get("totalUnrealizedPnl", 0)
            
            for pos in positions:
                state.positions[pos.get("symbol")] = pos
            
            return positions, total_pnl
        else:
            log("WARN", f"查询持仓失败: {response.status_code}")
            return [], 0
    except Exception as e:
        log("ERROR", f"查询持仓异常: {e}")
        return [], 0

def get_orderbook(symbol="BTCUSDT"):
    """查询盘口深度"""
    try:
        response = requests.get(
            f"{API_GATEWAY}/api/v1/match/orderbook/depth/{symbol}?depth=5",
            timeout=5
        )
        if response.ok:
            data = response.json()
            bids = data.get("bids", [])
            asks = data.get("asks", [])
            
            if bids and asks:
                best_bid = float(bids[0][0]) if isinstance(bids[0], list) else float(bids[0].get("price", 0))
                best_ask = float(asks[0][0]) if isinstance(asks[0], list) else float(asks[0].get("price", 0))
                
                # 如果是内部long格式，转换
                if best_bid > 100000000:
                    best_bid = best_bid / 100000000
                    best_ask = best_ask / 100000000
                    
                return {"bestBid": best_bid, "bestAsk": best_ask}
        return None
    except Exception as e:
        log("ERROR", f"查询盘口异常: {e}")
        return None

# ==================== WebSocket 处理 ====================

def on_private_message(ws, message):
    """处理私有推送消息"""
    try:
        msg = json.loads(message)
        state.ws_messages.append(msg)
        
        # 订单执行报告
        if msg.get("e") == "executionReport" or (msg.get("data") and msg.get("data").get("e") == "executionReport"):
            data = msg.get("data", msg)
            order_id = str(data.get("i"))
            order_status = data.get("X")
            filled_qty = data.get("z", 0)
            
            state.order_updates.append({
                "orderId": order_id,
                "status": order_status,
                "filledQuantity": filled_qty,
                "time": time.time()
            })
            
            log("INFO", f"📨 订单更新: orderId={order_id}, status={order_status}, filled={filled_qty}")
            
            # 更新本地订单状态
            if order_id in state.orders:
                state.orders[order_id]["status"] = order_status
                state.orders[order_id]["filledQuantity"] = filled_qty
        
        # 持仓更新
        elif msg.get("e") == "position" or (msg.get("data") and msg.get("data").get("e") == "position"):
            data = msg.get("data", msg)
            symbol = data.get("s")
            
            state.position_updates.append({
                "symbol": symbol,
                "data": data,
                "time": time.time()
            })
            
            log("INFO", f"📊 持仓更新: symbol={symbol}")
            
    except Exception as e:
        log("ERROR", f"处理消息异常: {e}")

def on_private_open(ws):
    """WebSocket 连接打开"""
    log("SUCCESS", "私有推送 WebSocket 已连接")
    
    # 订阅频道
    subscribe_msg = {
        "method": "SUBSCRIBE",
        "params": ["executionReport", "account", "position"],
        "id": int(time.time() * 1000)
    }
    ws.send(json.dumps(subscribe_msg))
    log("INFO", "已订阅: executionReport, account, position")

def on_private_close(ws, close_status_code, close_msg):
    """WebSocket 连接关闭"""
    log("WARN", f"私有推送 WebSocket 已关闭: {close_status_code}")

def on_private_error(ws, error):
    """WebSocket 错误"""
    log("ERROR", f"私有推送 WebSocket 错误: {error}")

def start_websocket():
    """启动 WebSocket 连接"""
    if not state.token:
        log("ERROR", "未登录，无法连接 WebSocket")
        return None
    
    ws_url = f"{PRIVATE_PUSH_WS}?token={state.token}"
    
    ws = websocket.WebSocketApp(
        ws_url,
        on_open=on_private_open,
        on_message=on_private_message,
        on_close=on_private_close,
        on_error=on_private_error
    )
    
    # 在后台线程运行
    def run():
        ws.run_forever()
    
    thread = threading.Thread(target=run, daemon=True)
    thread.start()
    
    return ws

# ==================== 测试用例 ====================

def test_login():
    """测试1: 用户登录"""
    log("TEST", "=" * 50)
    log("TEST", "测试1: 用户登录")
    log("TEST", "=" * 50)
    
    result = login()
    add_result("用户登录", result, f"userId={state.user_id}")
    return result

def test_get_balance():
    """测试2: 查询账户余额"""
    log("TEST", "=" * 50)
    log("TEST", "测试2: 查询账户余额")
    log("TEST", "=" * 50)
    
    balance = get_balance()
    if balance:
        available = float(balance.get("available", 0))
        add_result("查询余额", True, f"可用余额: {available:.2f} USDT")
        return True
    else:
        add_result("查询余额", False, "无法获取余额")
        return False

def test_get_orderbook():
    """测试3: 查询盘口深度"""
    log("TEST", "=" * 50)
    log("TEST", "测试3: 查询盘口深度")
    log("TEST", "=" * 50)
    
    orderbook = get_orderbook("BTCUSDT")
    if orderbook:
        log("INFO", f"买一: {orderbook['bestBid']:.2f}, 卖一: {orderbook['bestAsk']:.2f}")
        add_result("查询盘口", True, f"买一: {orderbook['bestBid']:.2f}, 卖一: {orderbook['bestAsk']:.2f}")
        return orderbook
    else:
        add_result("查询盘口", False, "无法获取盘口数据")
        return None

def test_submit_order_limit():
    """测试4: 提交限价单"""
    log("TEST", "=" * 50)
    log("TEST", "测试4: 提交限价单")
    log("TEST", "=" * 50)
    
    # 使用盘口价格下单
    orderbook = get_orderbook("BTCUSDT")
    if orderbook:
        # 买入：使用买一价
        buy_price = orderbook["bestBid"] - 100  # 比买一低一点，挂买单
    else:
        buy_price = 50000  # 默认价格
    
    order_id = submit_order(
        symbol="BTCUSDT",
        side="BUY",
        order_type="LIMIT",
        price=buy_price,
        quantity=0.01,  # 0.01 BTC
        leverage=10
    )
    
    if order_id:
        add_result("提交限价买单", True, f"orderId={order_id}, price={buy_price}")
        return order_id
    else:
        add_result("提交限价买单", False, "下单失败")
        return None

def test_submit_matching_order(order_id_to_match):
    """测试5: 提交对手方订单进行撮合"""
    log("TEST", "=" * 50)
    log("TEST", "测试5: 提交对手方订单进行撮合")
    log("TEST", "=" * 50)
    
    # 获取原订单信息
    original_order = state.orders.get(order_id_to_match, {})
    original_price = original_order.get("price", 50000)
    original_qty = original_order.get("quantity", 0.01)
    
    # 卖出：使用与买单相同或更低的价格确保成交
    sell_price = original_price + 50  # 比买单高一点确保撮合
    
    # 这里需要一个对手方账户来成交，或者我们假设系统有流动性
    # 实际测试中，我们提交一个卖单来与之前的买单撮合
    order_id = submit_order(
        symbol="BTCUSDT",
        side="SELL",
        order_type="LIMIT",
        price=sell_price,
        quantity=original_qty,
        leverage=10
    )
    
    if order_id:
        add_result("提交限价卖单(对手方)", True, f"orderId={order_id}, price={sell_price}")
        return order_id
    else:
        add_result("提交限价卖单(对手方)", False, "下单失败")
        return None

def test_check_order_status(order_id, expected_status=None, timeout=10):
    """测试6: 检查订单状态"""
    log("TEST", "=" * 50)
    log("TEST", "测试6: 检查订单状态")
    log("TEST", "=" * 50)
    
    start_time = time.time()
    last_status = None
    
    while time.time() - start_time < timeout:
        # 从 WebSocket 更新中查找
        for update in state.order_updates:
            if update["orderId"] == order_id:
                last_status = update["status"]
                log("INFO", f"订单 {order_id} 状态: {last_status}")
                
                if expected_status and last_status == expected_status:
                    add_result("订单状态检查", True, f"订单 {order_id} 状态为 {last_status}")
                    return True, last_status
        
        # 从 API 查询
        orders = get_orders("active" if expected_status != "FILLED" else "history")
        for order in orders:
            if str(order.get("orderId")) == str(order_id):
                last_status = order.get("status")
                log("INFO", f"订单 {order_id} 状态(来自API): {last_status}")
                
                if expected_status and last_status == expected_status:
                    add_result("订单状态检查", True, f"订单 {order_id} 状态为 {last_status}")
                    return True, last_status
        
        time.sleep(1)
    
    add_result("订单状态检查", expected_status is None, f"订单 {order_id} 最终状态: {last_status}")
    return False, last_status

def test_check_position():
    """测试7: 检查持仓"""
    log("TEST", "=" * 50)
    log("TEST", "测试7: 检查持仓")
    log("TEST", "=" * 50)
    
    # 等待一段时间让持仓更新
    time.sleep(2)
    
    positions, total_pnl = get_positions()
    
    if positions:
        for pos in positions:
            symbol = pos.get("symbol")
            side = pos.get("side")
            size = pos.get("size", 0)
            entry_price = pos.get("entryPrice", 0)
            unrealized_pnl = pos.get("unrealizedPnl", 0)
            
            log("INFO", f"持仓: {symbol} {side} 数量={size} 开仓价={entry_price} 未实现盈亏={unrealized_pnl}")
        
        add_result("持仓检查", True, f"共有 {len(positions)} 个持仓")
        return True
    else:
        add_result("持仓检查", False, "没有持仓数据")
        return False

def test_order_list_display():
    """测试8: 订单列表显示"""
    log("TEST", "=" * 50)
    log("TEST", "测试8: 订单列表显示")
    log("TEST", "=" * 50)
    
    # 查询当前委托
    active_orders = get_orders("active")
    log("INFO", f"当前委托数量: {len(active_orders)}")
    
    # 查询历史委托
    history_orders = get_orders("history")
    log("INFO", f"历史委托数量: {len(history_orders)}")
    
    total_orders = len(active_orders) + len(history_orders)
    add_result("订单列表显示", total_orders > 0, f"当前委托: {len(active_orders)}, 历史委托: {len(history_orders)}")
    return total_orders > 0

def print_test_summary():
    """打印测试总结"""
    log("TEST", "\n" + "=" * 60)
    log("TEST", "测试总结")
    log("TEST", "=" * 60)
    
    passed = sum(1 for r in state.test_results if r["passed"])
    failed = sum(1 for r in state.test_results if not r["passed"])
    total = len(state.test_results)
    
    for result in state.test_results:
        status = "✅" if result["passed"] else "❌"
        log("TEST", f"{status} {result['name']}: {result['details']}")
    
    log("TEST", "-" * 60)
    log("TEST", f"总计: {total} 个测试, 通过: {passed}, 失败: {failed}")
    
    if failed == 0:
        log("SUCCESS", "🎉 所有测试通过!")
    else:
        log("WARN", f"⚠️ 有 {failed} 个测试失败")

# ==================== 主测试流程 ====================

def run_tests():
    """运行完整测试流程"""
    log("INFO", "\n" + "=" * 60)
    log("INFO", "开始合约交易系统自动化测试")
    log("INFO", f"测试账号: {USERNAME}")
    log("INFO", f"API Gateway: {API_GATEWAY}")
    log("INFO", "=" * 60 + "\n")
    
    try:
        # 1. 登录
        if not test_login():
            log("ERROR", "登录失败，终止测试")
            print_test_summary()
            return
        
        # 2. 连接 WebSocket
        log("INFO", "连接私有推送 WebSocket...")
        ws = start_websocket()
        time.sleep(2)  # 等待连接建立
        
        # 3. 查询余额
        test_get_balance()
        
        # 4. 查询盘口
        orderbook = test_get_orderbook()
        
        # 5. 提交买单
        buy_order_id = test_submit_order_limit()
        if not buy_order_id:
            log("ERROR", "提交买单失败")
        else:
            time.sleep(2)  # 等待订单处理
            
            # 6. 检查订单状态（应该是 NEW 或 PARTIALLY_FILLED）
            test_check_order_status(buy_order_id, timeout=5)
            
            # 7. 提交卖单（尝试撮合）
            # 注意：这里可能需要另一个账户来成交
            # 如果是同一个账户，可能无法自成交（取决于系统规则）
            sell_order_id = test_submit_matching_order(buy_order_id)
            
            if sell_order_id:
                time.sleep(3)  # 等待撮合
                
                # 8. 再次检查订单状态
                test_check_order_status(buy_order_id, timeout=5)
                test_check_order_status(sell_order_id, timeout=5)
        
        # 9. 检查持仓
        test_check_position()
        
        # 10. 订单列表显示
        test_order_list_display()
        
        # 11. 等待更多 WebSocket 消息
        log("INFO", "等待更多推送消息...")
        time.sleep(3)
        
        # 打印 WebSocket 统计
        log("INFO", f"收到 {len(state.order_updates)} 个订单更新")
        log("INFO", f"收到 {len(state.position_updates)} 个持仓更新")
        
    except Exception as e:
        log("ERROR", f"测试过程发生异常: {e}")
        import traceback
        traceback.print_exc()
    
    finally:
        print_test_summary()

if __name__ == "__main__":
    # 检查 websocket-client 库
    try:
        import websocket
    except ImportError:
        print("请先安装 websocket-client: pip install websocket-client")
        exit(1)
    
    run_tests()
