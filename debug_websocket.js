/**
 * WebSocket 盘口推送诊断脚本
 * 在浏览器 Console 中运行此脚本来诊断问题
 */

(function() {
    'use strict';
    
    console.log('%c🔧 WebSocket 盘口推送诊断工具', 'font-size:16px;font-weight:bold;color:#58a6ff');
    console.log('%c=====================================', 'color:#8b949e');
    
    // 1. 检查 WebSocket 连接状态
    console.log('%c\n📡 步骤1: 检查 WebSocket 连接状态', 'font-size:14px;font-weight:bold;color:#d29922');
    
    const publicWsState = state.publicWs ? state.publicWs.readyState : -1;
    const privateWsState = state.privateWs ? state.privateWs.readyState : -1;
    
    const readyStateMap = {
        '-1': '未初始化',
        '0': 'CONNECTING (连接中)',
        '1': 'OPEN (已连接)',
        '2': 'CLOSING (关闭中)',
        '3': 'CLOSED (已关闭)'
    };
    
    console.log('公有推送 WebSocket 状态:', readyStateMap[publicWsState] || publicWsState);
    console.log('私有推送 WebSocket 状态:', readyStateMap[privateWsState] || privateWsState);
    
    // 2. 检查缓存数据
    console.log('%c\n💾 步骤2: 检查缓存数据', 'font-size:14px;font-weight:bold;color:#d29922');
    console.log('wsOrderBookData:', wsOrderBookData);
    console.log('currentOrderBook:', currentOrderBook);
    console.log('lastOrderBookUpdate:', lastOrderBookUpdate ? new Date(lastOrderBookUpdate).toLocaleTimeString() : '无');
    
    // 3. 检查配置
    console.log('%c\n⚙️ 步骤3: 检查配置', 'font-size:14px;font-weight:bold;color:#d29922');
    console.log('PUBLIC_PUSH_WS:', CONFIG.PUBLIC_PUSH_WS);
    console.log('API_GATEWAY:', CONFIG.API_GATEWAY);
    
    // 4. 手动重新订阅深度频道
    console.log('%c\n🔄 步骤4: 重新订阅深度频道', 'font-size:14px;font-weight:bold;color:#d29922');
    
    window.resubscribeDepth = function() {
        if (state.publicWs && state.publicWs.readyState === WebSocket.OPEN) {
            // 先取消订阅
            state.publicWs.send(JSON.stringify({
                method: 'UNSUBSCRIBE',
                params: ['depth.BTCUSDT'],
                id: Date.now()
            }));
            console.log('✅ 已发送取消订阅 depth.BTCUSDT');
            
            // 延迟后重新订阅
            setTimeout(() => {
                state.publicWs.send(JSON.stringify({
                    method: 'SUBSCRIBE',
                    params: ['depth.BTCUSDT'],
                    id: Date.now()
                }));
                console.log('✅ 已发送订阅 depth.BTCUSDT');
            }, 500);
        } else {
            console.error('❌ WebSocket 未连接，无法订阅');
        }
    };
    
    window.resubscribeDepth();
    
    // 5. 添加消息监听器来查看原始消息
    console.log('%c\n📨 步骤5: 添加消息监听器', 'font-size:14px;font-weight:bold;color:#d29922');
    
    window.debugMessageCount = 0;
    
    if (state.publicWs) {
        // 保存原始 onmessage
        const originalOnMessage = state.publicWs.onmessage;
        
        state.publicWs.onmessage = function(e) {
            window.debugMessageCount++;
            
            try {
                const msg = JSON.parse(e.data);
                
                // 只打印深度相关的消息
                const channel = msg.stream || msg.e;
                if (channel && (channel.includes('depth') || channel.includes('DEPTH'))) {
                    console.log('%c[深度消息 #' + window.debugMessageCount + ']', 'color:#3fb950;font-weight:bold', msg);
                }
                
                // 调用原始处理函数
                if (originalOnMessage) {
                    originalOnMessage(e);
                }
            } catch (err) {
                console.error('解析消息失败:', err);
            }
        };
        
        console.log('✅ 已添加消息监听器');
    }
    
    // 6. 手动刷新盘口显示
    console.log('%c\n🎨 步骤6: 手动刷新盘口显示', 'font-size:14px;font-weight:bold;color:#d29922');
    
    window.manualRefreshOrderBook = function() {
        if (wsOrderBookData && wsOrderBookData.bids && wsOrderBookData.asks) {
            console.log('使用缓存数据刷新盘口...');
            updateOrderBookDisplay(wsOrderBookData);
            console.log('✅ 盘口显示已刷新');
        } else {
            console.warn('⚠️ 无缓存数据，尝试从 HTTP 加载...');
            refreshOrderBookOnce();
        }
    };
    
    window.manualRefreshOrderBook();
    
    // 7. 测试 HTTP 盘口接口
    console.log('%c\n🌐 步骤7: 测试 HTTP 盘口接口', 'font-size:14px;font-weight:bold;color:#d29922');
    
    fetch(`${CONFIG.API_GATEWAY}/api/v1/match/orderbook/depth/BTCUSDT?depth=10`)
        .then(res => res.json())
        .then(data => {
            console.log('HTTP 盘口数据:', data);
            
            // 更新 WebSocket 缓存
            wsOrderBookData = {
                bids: data.bids || [],
                asks: data.asks || [],
                timestamp: Date.now()
            };
            lastOrderBookUpdate = Date.now();
            
            // 刷新显示
            updateOrderBookDisplay(wsOrderBookData);
            console.log('✅ 已使用 HTTP 数据更新盘口');
        })
        .catch(err => {
            console.error('❌ HTTP 盘口查询失败:', err);
        });
    
    // 8. 提供诊断函数
    console.log('%c\n🔍 可用诊断函数:', 'font-size:14px;font-weight:bold;color:#58a6ff');
    console.log('  resubscribeDepth()     - 重新订阅深度频道');
    console.log('  manualRefreshOrderBook() - 手动刷新盘口显示');
    console.log('  state.publicWs         - 查看 WebSocket 对象');
    console.log('  wsOrderBookData        - 查看盘口缓存数据');
    
    // 9. 设置定时检查
    console.log('%c\n⏱️ 步骤8: 设置定时检查', 'font-size:14px;font-weight:bold;color:#d29922');
    
    let checkCount = 0;
    const checkInterval = setInterval(() => {
        checkCount++;
        const publicStatus = state.publicWs ? state.publicWs.readyState : -1;
        const lastUpdate = lastOrderBookUpdate ? (Date.now() - lastOrderBookUpdate) / 1000 : 'N/A';
        
        console.log(`[检查 #${checkCount}] WebSocket状态: ${publicStatus}, 上次更新: ${typeof lastUpdate === 'number' ? lastUpdate.toFixed(1) + '秒前' : lastUpdate}`);
        
        // 如果 WebSocket 断开，尝试重连
        if (publicStatus === 3 || publicStatus === -1) {
            console.warn('⚠️ WebSocket 已断开，尝试重连...');
            connectPublicWS();
        }
        
        // 如果超过 10 秒没有更新，尝试重新订阅
        if (typeof lastUpdate === 'number' && lastUpdate > 10) {
            console.warn('⚠️ 超过 10 秒没有深度更新，尝试重新订阅...');
            window.resubscribeDepth();
        }
        
        // 最多检查 10 次
        if (checkCount >= 10) {
            clearInterval(checkInterval);
            console.log('%c✅ 定时检查结束', 'color:#3fb950');
        }
    }, 3000);
    
    console.log('%c\n=====================================', 'color:#8b949e');
    console.log('%c诊断完成！观察上方输出以定位问题。', 'color:#3fb950;font-weight:bold');
    
})();
