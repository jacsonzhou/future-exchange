/**
 * 合约交易系统 - 浏览器控制台测试脚本
 * 使用方法：
 * 1. 打开 http://localhost:3000/trading-test.html
 * 2. 按 F12 打开开发者工具
 * 3. 切换到 Console 标签
 * 4. 复制粘贴以下代码并回车运行
 */

(function() {
    'use strict';
    
    console.log('%c🚀 合约交易系统自动化测试开始', 'font-size:16px;font-weight:bold;color:#238636');
    console.log('%c========================================', 'color:#8b949e');
    
    const TEST_RESULTS = [];
    
    function log(level, message) {
        const timestamp = new Date().toLocaleTimeString('zh-CN');
        const colors = {
            info: 'color:#58a6ff',
            success: 'color:#3fb950;font-weight:bold',
            error: 'color:#f85149;font-weight:bold',
            warn: 'color:#d29922'
        };
        console.log(`%c[${timestamp}] [${level.toUpperCase()}] ${message}`, colors[level] || colors.info);
    }
    
    function addResult(testName, passed, details = '') {
        TEST_RESULTS.push({ name: testName, passed, details });
        const icon = passed ? '✅' : '❌';
        const color = passed ? '#3fb950' : '#f85149';
        console.log(`%c${icon} ${testName}: ${details}`, `color:${color}`);
    }
    
    // ==================== 测试1: 检查登录状态 ====================
    async function testLoginStatus() {
        log('info', '测试1: 检查登录状态');
        
        const token = localStorage.getItem('token');
        const userId = localStorage.getItem('userId');
        const username = localStorage.getItem('username');
        
        if (token && userId) {
            addResult('用户登录状态', true, `用户: ${username}, ID: ${userId}`);
            return { token, userId, username };
        } else {
            addResult('用户登录状态', false, '未登录，请先登录');
            return null;
        }
    }
    
    // ==================== 测试2: WebSocket 连接状态 ====================
    function testWebSocketStatus() {
        log('info', '测试2: 检查 WebSocket 连接状态');
        
        const publicWsStatus = document.getElementById('publicWsStatus')?.className || '';
        const privateWsStatus = document.getElementById('privateWsStatus')?.className || '';
        
        const publicConnected = publicWsStatus.includes('connected');
        const privateConnected = privateWsStatus.includes('connected');
        
        addResult('公有推送 WebSocket', publicConnected, publicConnected ? '已连接' : '未连接');
        addResult('私有推送 WebSocket', privateConnected, privateConnected ? '已连接' : '未连接');
        
        return { publicConnected, privateConnected };
    }
    
    // ==================== 测试3: 查询账户余额 ====================
    async function testBalance(auth) {
        log('info', '测试3: 查询账户余额');
        
        try {
            const res = await fetch(`${CONFIG.API_GATEWAY}/api/v1/account/balance/${auth.userId}`, {
                headers: { 'Authorization': `Bearer ${auth.token}` }
            });
            
            if (res.ok) {
                const data = await res.json();
                const snapshot = data.data || data;
                const available = parseFloat(snapshot.available || 0).toFixed(2);
                const frozen = parseFloat(snapshot.frozen || 0).toFixed(2);
                
                addResult('账户余额查询', true, `可用: ${available} USDT, 冻结: ${frozen} USDT`);
                return snapshot;
            } else {
                addResult('账户余额查询', false, `HTTP ${res.status}`);
                return null;
            }
        } catch (err) {
            addResult('账户余额查询', false, err.message);
            return null;
        }
    }
    
    // ==================== 测试4: 查询盘口深度 ====================
    async function testOrderBook() {
        log('info', '测试4: 查询盘口深度');
        
        try {
            const res = await fetch(`${CONFIG.API_GATEWAY}/api/v1/match/orderbook/depth/BTCUSDT?depth=5`);
            
            if (res.ok) {
                const data = await res.json();
                const bids = data.bids || [];
                const asks = data.asks || [];
                
                if (bids.length > 0 && asks.length > 0) {
                    const bestBid = parseFloat(bids[0][0]);
                    const bestAsk = parseFloat(asks[0][0]);
                    addResult('盘口深度查询', true, `买一: ${bestBid}, 卖一: ${bestAsk}`);
                    return { bestBid, bestAsk, bids, asks };
                } else {
                    addResult('盘口深度查询', false, '盘口数据为空');
                    return null;
                }
            } else {
                addResult('盘口深度查询', false, `HTTP ${res.status}`);
                return null;
            }
        } catch (err) {
            addResult('盘口深度查询', false, err.message);
            return null;
        }
    }
    
    // ==================== 测试5: 查询订单列表 ====================
    async function testOrders(auth) {
        log('info', '测试5: 查询订单列表');
        
        try {
            // 查询当前委托
            const activeRes = await fetch(
                `${CONFIG.API_GATEWAY}/api/v1/oms/order/list?limit=20&status=NEW,PENDING_RISK,FROZEN,PARTIALLY_FILLED`,
                { headers: { 'Authorization': `Bearer ${auth.token}` } }
            );
            
            // 查询历史委托
            const historyRes = await fetch(
                `${CONFIG.API_GATEWAY}/api/v1/oms/order/list?limit=20&status=FILLED,CANCELED,REJECTED`,
                { headers: { 'Authorization': `Bearer ${auth.token}` } }
            );
            
            let activeOrders = [];
            let historyOrders = [];
            
            if (activeRes.ok) {
                const data = await activeRes.json();
                activeOrders = data.orders || [];
            }
            
            if (historyRes.ok) {
                const data = await historyRes.json();
                historyOrders = data.orders || [];
            }
            
            addResult('订单列表查询', true, `当前委托: ${activeOrders.length}, 历史委托: ${historyOrders.length}`);
            
            // 显示订单详情
            if (activeOrders.length > 0) {
                console.log('%c📋 当前委托:', 'color:#58a6ff;font-weight:bold');
                activeOrders.slice(0, 5).forEach(order => {
                    const side = order.side === 'BUY' ? '买入' : '卖出';
                    const status = order.status;
                    console.log(`   ${side} ${order.symbol} @ ${order.price} 数量:${order.quantity} 状态:${status}`);
                });
            }
            
            if (historyOrders.length > 0) {
                console.log('%c📜 历史委托 (最近5条):', 'color:#8b949e;font-weight:bold');
                historyOrders.slice(0, 5).forEach(order => {
                    const side = order.side === 'BUY' ? '买入' : '卖出';
                    console.log(`   ${side} ${order.symbol} @ ${order.price} 状态:${order.status}`);
                });
            }
            
            return { activeOrders, historyOrders };
        } catch (err) {
            addResult('订单列表查询', false, err.message);
            return null;
        }
    }
    
    // ==================== 测试6: 查询持仓 ====================
    async function testPositions(auth) {
        log('info', '测试6: 查询持仓');
        
        try {
            const res = await fetch(
                // 持仓查询通过 JWT Token 认证，userId 由 Gateway 从 Token 解析透传
                `${CONFIG.API_GATEWAY}/api/v1/position/list`,
                { headers: { 'Authorization': `Bearer ${auth.token}` } }
            );
            
            if (res.ok) {
                const data = await res.json();
                const positions = data.positions || [];
                const totalPnl = parseFloat(data.totalUnrealizedPnl || 0);
                
                addResult('持仓查询', positions.length > 0, `持仓数量: ${positions.length}, 总未实现盈亏: ${totalPnl.toFixed(2)} USDT`);
                
                if (positions.length > 0) {
                    console.log('%c📊 持仓详情:', 'color:#3fb950;font-weight:bold');
                    positions.forEach(pos => {
                        const pnl = parseFloat(pos.unrealizedPnl || 0);
                        const pnlColor = pnl >= 0 ? '#3fb950' : '#f85149';
                        console.log(`   ${pos.symbol} ${pos.side} 数量:${pos.size} 开仓价:${pos.entryPrice} 未实现盈亏:%c${pnl.toFixed(2)} USDT`, `color:${pnlColor}`);
                    });
                }
                
                return positions;
            } else {
                addResult('持仓查询', false, `HTTP ${res.status}`);
                return null;
            }
        } catch (err) {
            addResult('持仓查询', false, err.message);
            return null;
        }
    }
    
    // ==================== 测试7: 提交新订单 ====================
    async function testSubmitOrder(auth, orderBook) {
        log('info', '测试7: 提交新订单');
        
        if (!orderBook) {
            addResult('提交订单', false, '无法获取盘口数据');
            return null;
        }
        
        // 使用买一价下单，确保挂在那里
        const price = orderBook.bestBid - 1000;  // 比买一低 1000
        const quantity = 0.01;  // 0.01 BTC
        
        const priceInt = Math.round(price * 100000000);
        const quantityInt = Math.round(quantity * 100000000);
        
        const request = {
            symbol: 'BTCUSDT',
            side: 'BUY',
            type: 'LIMIT',
            price: String(priceInt),
            quantity: String(quantityInt),
            leverage: 10,
            timeInForce: 'GTC',
            clientOrderId: `browser_test_${Date.now()}`
        };
        
        try {
            console.log(`提交订单: BUY BTCUSDT @ ${price} 数量:${quantity}`);
            
            const res = await fetch(`${CONFIG.API_GATEWAY}/api/order/create`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${auth.token}`,
                    'X-Idempotency-Key': `idem_${Date.now()}`
                },
                body: JSON.stringify(request)
            });
            
            const data = await res.json();
            
            if (data.success || data.code === 0 || data.code === 200) {
                const orderId = data.orderId || data.data?.orderId;
                addResult('提交订单', true, `订单ID: ${orderId}, 价格: ${price}`);
                
                // 等待几秒后刷新订单列表
                setTimeout(() => {
                    loadOrders('active');
                    refreshBalance();
                }, 2000);
                
                return orderId;
            } else {
                addResult('提交订单', false, data.message || data.errorMsg || '未知错误');
                return null;
            }
        } catch (err) {
            addResult('提交订单', false, err.message);
            return null;
        }
    }
    
    // ==================== 测试8: 监听 WebSocket 消息 ====================
    function testWebSocketMessages() {
        log('info', '测试8: 检查 WebSocket 消息接收');
        
        const initialOrderUpdates = state.orderUpdates?.length || 0;
        const initialPositionUpdates = state.positionUpdates?.length || 0;
        
        console.log(`当前已接收订单更新: ${initialOrderUpdates} 条`);
        console.log(`当前已接收持仓更新: ${initialPositionUpdates} 条`);
        
        addResult('WebSocket 消息', true, `订单更新: ${initialOrderUpdates}, 持仓更新: ${initialPositionUpdates}`);
        
        return { initialOrderUpdates, initialPositionUpdates };
    }
    
    // ==================== 打印测试总结 ====================
    function printSummary() {
        console.log('%c========================================', 'color:#8b949e');
        console.log('%c📋 测试总结', 'font-size:14px;font-weight:bold;color:#58a6ff');
        console.log('%c========================================', 'color:#8b949e');
        
        const passed = TEST_RESULTS.filter(r => r.passed).length;
        const failed = TEST_RESULTS.filter(r => !r.passed).length;
        
        TEST_RESULTS.forEach(r => {
            const icon = r.passed ? '✅' : '❌';
            const color = r.passed ? 'color:#3fb950' : 'color:#f85149';
            console.log(`%c${icon} ${r.name}: ${r.details}`, color);
        });
        
        console.log('%c----------------------------------------', 'color:#8b949e');
        
        if (failed === 0) {
            console.log(`%c🎉 所有测试通过! (${passed}/${TEST_RESULTS.length})`, 'font-size:14px;font-weight:bold;color:#3fb950');
        } else {
            console.log(`%c⚠️ 测试完成: 通过 ${passed}, 失败 ${failed} (共 ${TEST_RESULTS.length})`, 'font-size:14px;font-weight:bold;color:#d29922');
        }
        
        return { passed, failed, total: TEST_RESULTS.length };
    }
    
    // ==================== 运行所有测试 ====================
    async function runAllTests() {
        // 1. 检查登录状态
        const auth = await testLoginStatus();
        if (!auth) {
            console.log('%c❌ 请先登录后再运行测试', 'color:#f85149;font-size:14px;font-weight:bold');
            return;
        }
        
        // 2. WebSocket 状态
        testWebSocketStatus();
        
        // 3. 余额查询
        const balance = await testBalance(auth);
        
        // 4. 盘口查询
        const orderBook = await testOrderBook();
        
        // 5. 订单列表
        const orders = await testOrders(auth);
        
        // 6. 持仓查询
        const positions = await testPositions(auth);
        
        // 7. WebSocket 消息
        testWebSocketMessages();
        
        // 8. 提交新订单（可选）
        console.log('%c----------------------------------------', 'color:#8b949e');
        console.log('%c如需测试下单，请运行:', 'color:#d29922');
        console.log('%c  await testSubmitOrder(auth, orderBook);', 'color:#58a6ff');
        
        // 打印总结
        const summary = printSummary();
        
        return { auth, balance, orderBook, orders, positions, summary };
    }
    
    // 暴露测试函数到全局
    window.testTradingSystem = runAllTests;
    window.testSubmitOrder = testSubmitOrder;
    window.testBalance = testBalance;
    window.testOrders = testOrders;
    window.testPositions = testPositions;
    
    // 自动运行测试
    window.testTradingSystem();
    
})();
