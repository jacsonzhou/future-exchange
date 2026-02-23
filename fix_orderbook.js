/**
 * 盘口深度显示修复脚本
 * 如果盘口不更新，在浏览器 Console 中运行此脚本
 */

(function() {
    'use strict';
    
    console.log('%c🔧 开始修复盘口深度推送', 'font-size:16px;font-weight:bold;color:#238636');
    
    // ==================== 修复1: 增强消息处理 ====================
    
    // 重写 handlePublicMessage 函数，增强深度数据处理
    window.handlePublicMessageFixed = function(msg) {
        // 心跳响应
        if (msg.pong) return;
        
        // 处理批量消息
        if (msg.batch === true && Array.isArray(msg.messages)) {
            msg.messages.forEach(messageStr => {
                try {
                    const innerMsg = typeof messageStr === 'string' ? JSON.parse(messageStr) : messageStr;
                    handlePublicMessageFixed(innerMsg);
                } catch (err) {
                    console.error('解析批量消息失败:', err);
                }
            });
            return;
        }
        
        // 订阅确认
        if (msg.result === 'subscribed') {
            console.log('✅ 订阅成功:', msg.data?.subscribed || msg.data);
            return;
        }
        
        // 处理快照数据
        if (msg.snapshot === true || msg.data?.snapshot === true) {
            const channel = msg.stream || msg.e;
            if (channel && (channel.includes('depth') || channel.includes('DEPTH'))) {
                console.log('📸 收到深度快照');
                const depthData = msg.data || msg;
                updateDepthData({
                    bids: depthData.b || depthData.bids || [],
                    asks: depthData.a || depthData.asks || [],
                    timestamp: depthData.E || msg.E || Date.now()
                });
            }
            return;
        }
        
        // 处理频道数据 - 修复: 更宽松的频道匹配
        const channel = msg.stream || msg.e || '';
        
        if (!channel) {
            // 尝试从 data 中获取频道
            if (msg.data && (msg.data.e || msg.data.stream)) {
                const innerChannel = msg.data.e || msg.data.stream;
                if (innerChannel.includes('depth') || innerChannel.includes('DEPTH')) {
                    console.log('🔍 从 data 中发现深度频道:', innerChannel);
                    updateDepthData({
                        bids: msg.data.b || msg.data.bids || [],
                        asks: msg.data.a || msg.data.asks || [],
                        timestamp: msg.data.E || Date.now()
                    });
                }
            }
            return;
        }
        
        // 深度数据更新 - 修复: 支持更多格式
        if (channel.includes('depth') || channel.includes('DEPTH') || 
            (msg.data && (msg.data.b || msg.data.a || msg.data.bids || msg.data.asks))) {
            
            const depthData = msg.data || msg;
            
            // 支持多种字段名
            let bids = depthData.b || depthData.bids || depthData.B || depthData.BIDS || [];
            let asks = depthData.a || depthData.asks || depthData.A || depthData.ASKS || [];
            
            // 处理字符串格式的数字
            const normalizeItem = (item) => {
                if (Array.isArray(item)) {
                    return [parseFloat(item[0]), parseFloat(item[1])];
                }
                if (typeof item === 'object' && item !== null) {
                    return [parseFloat(item.price || item.p || 0), parseFloat(item.qty || item.q || item.quantity || 0)];
                }
                return item;
            };
            
            bids = bids.map(normalizeItem).filter(item => item[0] > 0 && item[1] > 0);
            asks = asks.map(normalizeItem).filter(item => item[0] > 0 && item[1] > 0);
            
            console.log(`📊 深度更新: channel=${channel}, bids=${bids.length}, asks=${asks.length}`);
            
            if (bids.length > 0 || asks.length > 0) {
                updateDepthData({
                    bids: bids,
                    asks: asks,
                    timestamp: depthData.E || msg.E || Date.now()
                });
            }
        }
        
        // 成交数据
        else if (channel.includes('trade') || channel.includes('TRADE')) {
            const tradeData = msg.data || msg;
            updateTrades(tradeData);
        }
        
        // K线数据
        else if (channel.includes('kline') || channel.includes('KLINE')) {
            updateKline(msg.data || msg);
        }
    };
    
    // 统一的深度数据更新函数
    window.updateDepthData = function(data) {
        if (!data || (!data.bids && !data.asks)) {
            console.warn('⚠️ 无效的深度数据');
            return;
        }
        
        wsOrderBookData = {
            bids: data.bids || wsOrderBookData?.bids || [],
            asks: data.asks || wsOrderBookData?.asks || [],
            timestamp: data.timestamp || Date.now()
        };
        lastOrderBookUpdate = Date.now();
        
        // 更新显示
        updateOrderBookDisplay(wsOrderBookData);
        
        console.log('✅ 盘口已更新:', {
            bids: wsOrderBookData.bids.length,
            asks: wsOrderBookData.asks.length,
            time: new Date().toLocaleTimeString()
        });
    };
    
    // ==================== 修复2: 替换 WebSocket onmessage ====================
    
    if (state.publicWs) {
        console.log('🔄 替换 WebSocket 消息处理器...');
        
        // 保存旧的处理函数
        const oldOnMessage = state.publicWs.onmessage;
        
        state.publicWs.onmessage = function(e) {
            try {
                const msg = JSON.parse(e.data);
                
                // 打印所有消息用于调试
                if (msg.stream || msg.e) {
                    console.log('📨 WebSocket 消息:', msg.stream || msg.e, msg);
                }
                
                // 使用修复后的处理器
                handlePublicMessageFixed(msg);
                
            } catch (err) {
                console.error('❌ 处理消息失败:', err);
            }
        };
        
        console.log('✅ WebSocket 消息处理器已替换');
    } else {
        console.warn('⚠️ WebSocket 未连接');
    }
    
    // ==================== 修复3: 强制刷新盘口显示 ====================
    
    window.forceRefreshOrderBook = async function() {
        console.log('🔄 强制刷新盘口...');
        
        // 方法1: 从 HTTP 接口获取
        try {
            const res = await fetch(`${CONFIG.API_GATEWAY}/api/v1/match/orderbook/depth/BTCUSDT?depth=20`);
            if (res.ok) {
                const data = await res.json();
                console.log('📥 HTTP 盘口数据:', data);
                
                wsOrderBookData = {
                    bids: data.bids || [],
                    asks: data.asks || [],
                    timestamp: Date.now()
                };
                lastOrderBookUpdate = Date.now();
                
                updateOrderBookDisplay(wsOrderBookData);
                console.log('✅ 盘口已从 HTTP 刷新');
                return;
            }
        } catch (err) {
            console.error('❌ HTTP 刷新失败:', err);
        }
        
        // 方法2: 使用缓存数据
        if (wsOrderBookData) {
            updateOrderBookDisplay(wsOrderBookData);
            console.log('✅ 盘口已从缓存刷新');
        } else {
            console.warn('⚠️ 无数据可刷新');
        }
    };
    
    // ==================== 修复4: 重新连接 WebSocket ====================
    
    window.reconnectAndSubscribe = function() {
        console.log('🔄 重新连接 WebSocket...');
        
        // 关闭现有连接
        if (state.publicWs) {
            state.publicWs.close();
            state.publicWs = null;
        }
        
        // 重新连接
        connectPublicWS();
        
        // 延迟后检查并重新订阅
        setTimeout(() => {
            if (state.publicWs && state.publicWs.readyState === WebSocket.OPEN) {
                console.log('📡 重新订阅深度频道...');
                state.publicWs.send(JSON.stringify({
                    method: 'SUBSCRIBE',
                    params: ['depth.BTCUSDT', 'trade.BTCUSDT'],
                    id: Date.now()
                }));
            }
        }, 1000);
    };
    
    // ==================== 修复5: 增强盘口显示函数 ====================
    
    // 确保 updateOrderBookDisplay 函数正常工作
    window.updateOrderBookDisplaySafe = function(data) {
        if (!data) {
            console.warn('⚠️ updateOrderBookDisplay: data is null');
            return;
        }
        
        try {
            // 卖单 (Asks)
            if (data.asks && data.asks.length > 0) {
                const asks = data.asks.slice(0, 8).reverse();
                let asksHtml = '';
                asks.forEach((item) => {
                    const price = Array.isArray(item) ? item[0] : (item.price || item[0]);
                    const qty = Array.isArray(item) ? item[1] : (item.qty || item[1]);
                    
                    const priceNum = parseFloat(price);
                    const qtyNum = parseFloat(qty);
                    
                    if (!isNaN(priceNum) && !isNaN(qtyNum)) {
                        asksHtml += `
                            <div class="orderbook-row orderbook-ask">
                                <span class="orderbook-price">${formatPrice(priceNum)}</span>
                                <span class="orderbook-qty">${formatQty(qtyNum)}</span>
                                <span class="orderbook-total">${(priceNum * qtyNum).toFixed(2)}</span>
                            </div>
                        `;
                    }
                });
                document.getElementById('orderbookAsks').innerHTML = asksHtml || '<div style="text-align:center;padding:10px;color:#8b949e;">暂无卖单</div>';
            } else {
                document.getElementById('orderbookAsks').innerHTML = '<div style="text-align:center;padding:10px;color:#8b949e;">暂无卖单</div>';
            }
            
            // 买单 (Bids)
            if (data.bids && data.bids.length > 0) {
                const bids = data.bids.slice(0, 8);
                let bidsHtml = '';
                bids.forEach((item) => {
                    const price = Array.isArray(item) ? item[0] : (item.price || item[0]);
                    const qty = Array.isArray(item) ? item[1] : (item.qty || item[1]);
                    
                    const priceNum = parseFloat(price);
                    const qtyNum = parseFloat(qty);
                    
                    if (!isNaN(priceNum) && !isNaN(qtyNum)) {
                        bidsHtml += `
                            <div class="orderbook-row orderbook-bid">
                                <span class="orderbook-price">${formatPrice(priceNum)}</span>
                                <span class="orderbook-qty">${formatQty(qtyNum)}</span>
                                <span class="orderbook-total">${(priceNum * qtyNum).toFixed(2)}</span>
                            </div>
                        `;
                    }
                });
                document.getElementById('orderbookBids').innerHTML = bidsHtml || '<div style="text-align:center;padding:10px;color:#8b949e;">暂无买单</div>';
            } else {
                document.getElementById('orderbookBids').innerHTML = '<div style="text-align:center;padding:10px;color:#8b949e;">暂无买单</div>';
            }
            
            // 价差
            if (data.asks && data.bids && data.asks[0] && data.bids[0]) {
                const bestAsk = parseFloat(Array.isArray(data.asks[0]) ? data.asks[0][0] : data.asks[0]);
                const bestBid = parseFloat(Array.isArray(data.bids[0]) ? data.bids[0][0] : data.bids[0]);
                if (!isNaN(bestAsk) && !isNaN(bestBid)) {
                    document.getElementById('orderbookSpread').textContent = `价差: ${(bestAsk - bestBid).toFixed(2)} USDT`;
                }
            }
            
            console.log('✅ 盘口显示已更新');
            
        } catch (err) {
            console.error('❌ 更新盘口显示失败:', err);
        }
    };
    
    // 替换原函数
    window._originalUpdateOrderBookDisplay = updateOrderBookDisplay;
    window.updateOrderBookDisplay = window.updateOrderBookDisplaySafe;
    
    // ==================== 立即执行修复 ====================
    
    console.log('🚀 立即执行修复...');
    
    // 1. 强制刷新盘口
    window.forceRefreshOrderBook();
    
    // 2. 如果没有 WebSocket 连接，重新连接
    if (!state.publicWs || state.publicWs.readyState !== WebSocket.OPEN) {
        console.log('⚠️ WebSocket 未连接，执行重连...');
        window.reconnectAndSubscribe();
    }
    
    console.log('%c\n=====================================', 'color:#8b949e');
    console.log('%c✅ 修复完成！', 'font-size:14px;font-weight:bold;color:#238636');
    console.log('%c\n可用函数:', 'color:#58a6ff;font-weight:bold');
    console.log('  forceRefreshOrderBook()  - 强制刷新盘口');
    console.log('  reconnectAndSubscribe()  - 重新连接并订阅');
    console.log('  updateDepthData(data)    - 手动更新深度数据');
    console.log('  handlePublicMessageFixed(msg) - 修复的消息处理器');
    
})();
