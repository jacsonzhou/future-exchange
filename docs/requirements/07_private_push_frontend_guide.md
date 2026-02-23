# 私有推送前端实现指南

> **版本**: v1.0.0  
> **目标读者**: 前端开发工程师  
> **最后更新**: 2026-02-19

---

## 1. 概述

本文档提供私有推送系统的前端实现指南，包含：
- WebSocket 连接管理
- 订单状态同步机制
- 部分成交处理
- 断线重连与恢复

---

## 2. 核心概念

### 2.1 订单状态机

```
NEW (新建)
  │
  ├───▶ REJECTED (拒绝)
  │
  ├───▶ CANCELED (撤单)
  │
  └───▶ PARTIALLY_FILLED (部分成交)
          │
          ├───▶ CANCELED (撤单剩余)
          │
          └───▶ FILLED (完全成交)
```

### 2.2 部分成交的核心问题

**问题**: 如何保证订单状态一致性？

**解决方案**:
1. 服务端推送 `executionReport` 包含 **累计已成交数量** (z)
2. 客户端维护 `seq` 序列号，检测乱序和重复
3. 断线重连后拉取订单快照进行同步

---

## 3. TypeScript 实现

### 3.1 WebSocket 连接管理器

```typescript
// types.ts
export interface ExecutionReport {
  e: 'executionReport';
  E: number;              // 事件时间
  s: string;              // 交易对
  i: number;              // 订单ID
  c: string;              // 客户端订单ID
  S: 'BUY' | 'SELL';     // 方向
  o: string;              // 订单类型
  f: string;              // 有效时间
  q: string;              // 原始数量
  p: string;              // 价格
  X: OrderStatus;         // 订单状态
  x: ExecutionType;       // 执行类型
  z: string;              // 累计已成交
  Z: string;              // 累计成交金额
  l: string;              // 本次成交数量
  L: string;              // 本次成交价格
  n: string;              // 手续费
  N: string;              // 手续费资产
  T: number;              // 成交时间
  t: number;              // 成交ID
  seq: number;            // 序列号
}

export type OrderStatus = 
  | 'NEW' 
  | 'PARTIALLY_FILLED' 
  | 'FILLED' 
  | 'CANCELED' 
  | 'REJECTED' 
  | 'EXPIRED';

export type ExecutionType = 
  | 'NEW' 
  | 'CANCELED' 
  | 'REPLACED' 
  | 'TRADE' 
  | 'EXPIRED';

export interface Order {
  orderId: number;
  clientOrderId: string;
  symbol: string;
  side: 'BUY' | 'SELL';
  type: string;
  price: string;
  quantity: string;
  filledQuantity: string;
  status: OrderStatus;
  createdTime: number;
  updatedTime: number;
  seq: number;
}
```

### 3.2 WebSocket 管理器

```typescript
// websocket-manager.ts
import { EventEmitter } from 'events';

export class WebSocketManager extends EventEmitter {
  private ws: WebSocket | null = null;
  private url: string;
  private token: string;
  private reconnectAttempts = 0;
  private maxReconnectAttempts = 10;
  private reconnectDelay = 1000;
  private heartbeatInterval: NodeJS.Timeout | null = null;
  private pingId = 0;
  private messageId = 0;
  private pendingMessages = new Map<number, (response: any) => void>();
  
  constructor(url: string, token: string) {
    super();
    this.url = url;
    this.token = token;
  }
  
  connect(): void {
    try {
      // 添加 token 到 URL
      const wsUrl = `${this.url}?token=${this.token}`;
      this.ws = new WebSocket(wsUrl);
      
      this.ws.onopen = this.handleOpen.bind(this);
      this.ws.onmessage = this.handleMessage.bind(this);
      this.ws.onclose = this.handleClose.bind(this);
      this.ws.onerror = this.handleError.bind(this);
      
    } catch (error) {
      console.error('[WebSocket] Connection error:', error);
      this.scheduleReconnect();
    }
  }
  
  private handleOpen(): void {
    console.log('[WebSocket] Connected');
    this.reconnectAttempts = 0;
    
    // 启动心跳
    this.startHeartbeat();
    
    // 通知连接成功
    this.emit('connected');
  }
  
  private handleMessage(event: MessageEvent): void {
    try {
      const data = JSON.parse(event.data);
      
      // 处理 ACK 响应
      if (data.id !== undefined && this.pendingMessages.has(data.id)) {
        const resolve = this.pendingMessages.get(data.id);
        this.pendingMessages.delete(data.id);
        resolve?.(data.result);
        return;
      }
      
      // 处理推送消息
      if (data.stream) {
        this.emit('message', data.stream, data.data, data.seq);
        
        // 发送 ACK
        if (data.seq) {
          this.send({
            method: 'ACK',
            seq: data.seq,
            id: ++this.messageId
          });
        }
        return;
      }
      
      // 处理连接确认
      if (data.e === 'connectionAck') {
        this.emit('connectionAck', data);
        return;
      }
      
      // 处理错误
      if (data.error) {
        this.emit('error', new Error(data.error));
      }
      
    } catch (error) {
      console.error('[WebSocket] Message parse error:', error);
    }
  }
  
  private handleClose(event: CloseEvent): void {
    console.log('[WebSocket] Closed:', event.code, event.reason);
    this.stopHeartbeat();
    this.emit('disconnected');
    this.scheduleReconnect();
  }
  
  private handleError(error: Event): void {
    console.error('[WebSocket] Error:', error);
    this.emit('error', error);
  }
  
  private scheduleReconnect(): void {
    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      console.error('[WebSocket] Max reconnect attempts reached');
      this.emit('reconnectFailed');
      return;
    }
    
    const delay = Math.min(
      this.reconnectDelay * Math.pow(2, this.reconnectAttempts),
      30000 // 最大30秒
    );
    
    console.log(`[WebSocket] Reconnecting in ${delay}ms...`);
    
    setTimeout(() => {
      this.reconnectAttempts++;
      this.connect();
    }, delay);
  }
  
  private startHeartbeat(): void {
    this.heartbeatInterval = setInterval(() => {
      this.send({
        method: 'PING',
        id: ++this.pingId
      });
    }, 30000); // 30秒心跳
  }
  
  private stopHeartbeat(): void {
    if (this.heartbeatInterval) {
      clearInterval(this.heartbeatInterval);
      this.heartbeatInterval = null;
    }
  }
  
  send(data: any): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(data));
    } else {
      console.warn('[WebSocket] Cannot send, not connected');
    }
  }
  
  async subscribe(channels: string[]): Promise<void> {
    return new Promise((resolve) => {
      const id = ++this.messageId;
      this.pendingMessages.set(id, resolve);
      
      this.send({
        method: 'SUBSCRIBE',
        params: channels,
        id
      });
      
      // 5秒超时
      setTimeout(() => {
        if (this.pendingMessages.has(id)) {
          this.pendingMessages.delete(id);
          console.warn('[WebSocket] Subscribe timeout');
        }
      }, 5000);
    });
  }
  
  disconnect(): void {
    this.stopHeartbeat();
    this.ws?.close();
    this.ws = null;
  }
}
```

### 3.3 订单状态管理器 (核心)

```typescript
// order-state-manager.ts
import { EventEmitter } from 'events';
import { WebSocketManager } from './websocket-manager';
import { ExecutionReport, Order, OrderStatus } from './types';

/**
 * 订单状态管理器
 * 
 * 核心职责:
 * 1. 维护本地订单状态
 * 2. 处理 executionReport 更新
 * 3. 检测乱序和重复
 * 4. 断线重连后同步
 */
export class OrderStateManager extends EventEmitter {
  private ws: WebSocketManager;
  
  // 订单存储: orderId -> Order
  private orders = new Map<number, Order>();
  
  // 客户端订单ID映射: clientOrderId -> orderId
  private clientOrderIdMap = new Map<string, number>();
  
  // 已处理的最大序列号 (用于去重)
  private lastSeq = 0;
  
  // 待确认的消息序列号
  private pendingAcks = new Set<number>();
  
  // 本地订单ID生成器 (临时ID)
  private tempOrderIdCounter = -1;
  
  constructor(ws: WebSocketManager) {
    super();
    this.ws = ws;
    this.bindEvents();
  }
  
  private bindEvents(): void {
    // 监听 executionReport
    this.ws.on('message', (stream: string, data: ExecutionReport, seq: number) => {
      if (stream === 'executionReport') {
        this.handleExecutionReport(data, seq);
      }
    });
    
    // 监听重连
    this.ws.on('connected', () => {
      this.syncAfterReconnect();
    });
  }
  
  /**
   * 处理 executionReport (核心方法)
   */
  private handleExecutionReport(report: ExecutionReport, seq: number): void {
    // 1. 检查序列号 (去重和乱序检测)
    if (seq <= this.lastSeq) {
      console.warn('[OrderManager] Duplicate or out-of-order message, seq:', seq);
      return;
    }
    this.lastSeq = seq;
    
    const orderId = report.i;
    const status = report.X;
    
    console.log(`[OrderManager] Received execution report, orderId=${orderId}, status=${status}, seq=${seq}`);
    
    // 2. 查找或创建订单
    let order = this.orders.get(orderId);
    
    if (!order) {
      // 新订单
      order = {
        orderId: orderId,
        clientOrderId: report.c,
        symbol: report.s,
        side: report.S,
        type: report.o,
        price: report.p,
        quantity: report.q,
        filledQuantity: '0',
        status: 'NEW',
        createdTime: report.E,
        updatedTime: report.E,
        seq: seq
      };
      this.orders.set(orderId, order);
      this.clientOrderIdMap.set(report.c, orderId);
      
      this.emit('orderCreated', order);
    }
    
    // 3. 更新订单状态
    const oldStatus = order.status;
    order.status = status;
    order.filledQuantity = report.z;  // 使用服务端累计值
    order.updatedTime = report.E;
    order.seq = seq;
    
    // 4. 根据状态处理
    switch (status) {
      case 'PARTIALLY_FILLED':
        this.handlePartiallyFilled(order, report, oldStatus);
        break;
        
      case 'FILLED':
        this.handleFilled(order, report, oldStatus);
        break;
        
      case 'CANCELED':
        this.handleCanceled(order, report, oldStatus);
        break;
        
      case 'REJECTED':
        this.handleRejected(order, report);
        break;
        
      case 'NEW':
        // 新订单确认
        this.emit('orderNew', order);
        break;
    }
    
    // 5. 触发通用更新事件
    this.emit('orderUpdated', order, report);
  }
  
  /**
   * 处理部分成交
   */
  private handlePartiallyFilled(
    order: Order, 
    report: ExecutionReport, 
    oldStatus: OrderStatus
  ): void {
    console.log(`[OrderManager] Order partially filled, orderId=${order.orderId}, ` +
                `filled=${report.z}/${order.quantity}, ` +
                `thisTrade=${report.l} @ ${report.L}`);
    
    // 触发部分成交事件
    this.emit('orderPartiallyFilled', {
      order,
      lastFilledQty: report.l,
      lastFilledPrice: report.L,
      cumulativeFilledQty: report.z,
      cumulativeFilledAmount: report.Z,
      fee: report.n,
      tradeId: report.t,
      tradeTime: report.T
    });
    
    // 如果是首次成交，触发首次成交事件
    if (oldStatus === 'NEW') {
      this.emit('orderFirstFill', order);
    }
  }
  
  /**
   * 处理完全成交
   */
  private handleFilled(
    order: Order, 
    report: ExecutionReport, 
    oldStatus: OrderStatus
  ): void {
    console.log(`[OrderManager] Order filled, orderId=${order.orderId}, ` +
                `totalFilled=${report.z}`);
    
    // 触发完全成交事件
    this.emit('orderFilled', {
      order,
      lastFilledQty: report.l,
      lastFilledPrice: report.L,
      cumulativeFilledQty: report.z,
      cumulativeFilledAmount: report.Z,
      fee: report.n,
      tradeId: report.t,
      tradeTime: report.T
    });
    
    // 从活跃订单中移除 (稍后)
    setTimeout(() => {
      this.moveToHistory(order);
    }, 0);
  }
  
  /**
   * 处理撤单
   */
  private handleCanceled(
    order: Order, 
    report: ExecutionReport, 
    oldStatus: OrderStatus
  ): void {
    console.log(`[OrderManager] Order canceled, orderId=${order.orderId}, ` +
                `filledBeforeCancel=${order.filledQuantity}`);
    
    this.emit('orderCanceled', {
      order,
      filledQtyBeforeCancel: order.filledQuantity
    });
    
    // 移到历史
    this.moveToHistory(order);
  }
  
  /**
   * 处理拒绝
   */
  private handleRejected(order: Order, report: ExecutionReport): void {
    console.log(`[OrderManager] Order rejected, orderId=${order.orderId}`);
    
    this.emit('orderRejected', {
      order,
      errorCode: report.errorCode,
      errorMsg: report.errorMsg
    });
    
    // 移除订单
    this.orders.delete(order.orderId);
    this.clientOrderIdMap.delete(order.clientOrderId);
  }
  
  /**
   * 移到历史
   */
  private moveToHistory(order: Order): void {
    // 从活跃订单移除
    this.orders.delete(order.orderId);
    this.clientOrderIdMap.delete(order.clientOrderId);
    
    this.emit('orderMovedToHistory', order);
  }
  
  /**
   * 断线重连后同步
   */
  private async syncAfterReconnect(): Promise<void> {
    console.log('[OrderManager] Syncing after reconnect...');
    
    try {
      // 拉取当前订单快照
      const response = await fetch('/api/v1/private/orders/open', {
        headers: {
          'Authorization': `Bearer ${this.getToken()}`
        }
      });
      
      if (!response.ok) {
        throw new Error('Failed to fetch orders');
      }
      
      const result = await response.json();
      const serverOrders: Order[] = result.data;
      
      // 合并服务端和本地状态
      for (const serverOrder of serverOrders) {
        const localOrder = this.orders.get(serverOrder.orderId);
        
        if (!localOrder) {
          // 本地没有，添加
          this.orders.set(serverOrder.orderId, serverOrder);
          this.clientOrderIdMap.set(serverOrder.clientOrderId, serverOrder.orderId);
        } else if (serverOrder.seq > localOrder.seq) {
          // 服务端更新，覆盖本地
          this.orders.set(serverOrder.orderId, serverOrder);
        }
        // 否则保持本地状态 (可能服务端还没收到最新推送)
      }
      
      // 清理本地已完成的订单 (如果服务端没有)
      const serverOrderIds = new Set(serverOrders.map(o => o.orderId));
      for (const [orderId, order] of this.orders) {
        if (!serverOrderIds.has(orderId) && order.status !== 'FILLED' && order.status !== 'CANCELED') {
          // 服务端没有这个订单，可能是已完成但没收到推送
          this.orders.delete(orderId);
          this.clientOrderIdMap.delete(order.clientOrderId);
        }
      }
      
      this.emit('syncCompleted', this.getAllOrders());
      console.log('[OrderManager] Sync completed, orders:', this.orders.size);
      
    } catch (error) {
      console.error('[OrderManager] Sync failed:', error);
      this.emit('syncFailed', error);
    }
  }
  
  // ==================== 公共方法 ====================
  
  /**
   * 获取所有订单
   */
  getAllOrders(): Order[] {
    return Array.from(this.orders.values());
  }
  
  /**
   * 获取活跃订单 (未完成的)
   */
  getActiveOrders(): Order[] {
    return this.getAllOrders().filter(o => 
      o.status !== 'FILLED' && 
      o.status !== 'CANCELED' && 
      o.status !== 'REJECTED'
    );
  }
  
  /**
   * 获取指定订单
   */
  getOrder(orderId: number): Order | undefined {
    return this.orders.get(orderId);
  }
  
  /**
   * 根据客户端订单ID获取
   */
  getOrderByClientId(clientOrderId: string): Order | undefined {
    const orderId = this.clientOrderIdMap.get(clientOrderId);
    return orderId ? this.orders.get(orderId) : undefined;
  }
  
  /**
   * 本地创建订单 (优化响应速度)
   */
  createLocalOrder(params: {
    clientOrderId: string;
    symbol: string;
    side: 'BUY' | 'SELL';
    type: string;
    price: string;
    quantity: string;
  }): Order {
    const tempOrderId = this.tempOrderIdCounter--;
    
    const order: Order = {
      orderId: tempOrderId,
      clientOrderId: params.clientOrderId,
      symbol: params.symbol,
      side: params.side,
      type: params.type,
      price: params.price,
      quantity: params.quantity,
      filledQuantity: '0',
      status: 'NEW',
      createdTime: Date.now(),
      updatedTime: Date.now(),
      seq: 0
    };
    
    this.orders.set(tempOrderId, order);
    this.clientOrderIdMap.set(params.clientOrderId, tempOrderId);
    
    this.emit('orderCreated', order);
    
    return order;
  }
  
  /**
   * 更新本地订单ID (当服务端返回真实ID)
   */
  updateOrderId(clientOrderId: string, realOrderId: number): void {
    const tempOrderId = this.clientOrderIdMap.get(clientOrderId);
    if (!tempOrderId) return;
    
    const order = this.orders.get(tempOrderId);
    if (!order) return;
    
    // 删除旧的，添加新的
    this.orders.delete(tempOrderId);
    order.orderId = realOrderId;
    this.orders.set(realOrderId, order);
    this.clientOrderIdMap.set(clientOrderId, realOrderId);
  }
  
  private getToken(): string {
    // 从存储或上下文获取 token
    return '';
  }
}
```

### 3.4 使用示例

```typescript
// app.ts
import { WebSocketManager } from './websocket-manager';
import { OrderStateManager } from './order-state-manager';

async function main() {
  const token = 'your-jwt-token';
  const ws = new WebSocketManager('wss://api.exchange.com/ws/private', token);
  const orderManager = new OrderStateManager(ws);
  
  // 监听订单事件
  orderManager.on('orderCreated', (order) => {
    console.log('订单创建:', order);
    // UI: 添加到"当前委托"列表
  });
  
  orderManager.on('orderPartiallyFilled', ({ order, lastFilledQty, lastFilledPrice }) => {
    console.log(`订单部分成交: ${lastFilledQty} @ ${lastFilledPrice}`);
    // UI: 更新已成交数量
    // 显示: "已成交 0.3/1.0 (30%)"
  });
  
  orderManager.on('orderFilled', ({ order }) => {
    console.log('订单完全成交:', order);
    // UI: 从"当前委托"移除，添加到"历史委托"
  });
  
  orderManager.on('orderCanceled', ({ order, filledQtyBeforeCancel }) => {
    console.log('订单已撤单, 撤单前已成交:', filledQtyBeforeCancel);
    // UI: 移到历史，显示"已撤单 (部分成交)"
  });
  
  // 连接并订阅
  ws.connect();
  
  ws.on('connectionAck', async () => {
    // 订阅 executionReport 频道
    await ws.subscribe(['executionReport', 'account']);
    console.log('已订阅订单推送');
  });
  
  // 下单示例
  async function placeOrder() {
    // 1. 本地创建订单 (立即显示在UI)
    const localOrder = orderManager.createLocalOrder({
      clientOrderId: `order_${Date.now()}`,
      symbol: 'BTCUSDT',
      side: 'BUY',
      type: 'LIMIT',
      price: '50000.00',
      quantity: '1.0'
    });
    
    try {
      // 2. 发送到服务端
      const response = await fetch('/api/v1/order', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${token}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          clientOrderId: localOrder.clientOrderId,
          symbol: localOrder.symbol,
          side: localOrder.side,
          type: localOrder.type,
          price: localOrder.price,
          quantity: localOrder.quantity
        })
      });
      
      const result = await response.json();
      
      if (result.code === 0) {
        // 3. 更新本地订单ID
        orderManager.updateOrderId(localOrder.clientOrderId, result.data.orderId);
        console.log('下单成功, orderId:', result.data.orderId);
      } else {
        // 下单失败，移除本地订单
        console.error('下单失败:', result.msg);
      }
      
    } catch (error) {
      console.error('下单请求失败:', error);
    }
  }
  
  // 执行下单
  await placeOrder();
}

main();
```

---

## 4. UI 组件建议

### 4.1 当前委托列表

```tsx
// OrderList.tsx
import React from 'react';
import { Order } from './types';

interface OrderListProps {
  orders: Order[];
  onCancel: (orderId: number) => void;
}

export const OrderList: React.FC<OrderListProps> = ({ orders, onCancel }) => {
  return (
    <table>
      <thead>
        <tr>
          <th>时间</th>
          <th>交易对</th>
          <th>方向</th>
          <th>价格</th>
          <th>数量</th>
          <th>已成交</th>
          <th>状态</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        {orders.map(order => (
          <tr key={order.orderId}>
            <td>{formatTime(order.createdTime)}</td>
            <td>{order.symbol}</td>
            <td className={order.side === 'BUY' ? 'buy' : 'sell'}>
              {order.side}
            </td>
            <td>{order.price}</td>
            <td>{order.quantity}</td>
            <td>
              {/* 进度条显示成交进度 */}
              <div className="fill-progress">
                <div 
                  className="fill-bar" 
                  style={{ width: `${getFillPercentage(order)}%` }}
                />
                <span>{order.filledQuantity}/{order.quantity}</span>
              </div>
            </td>
            <td>
              <OrderStatusBadge status={order.status} />
            </td>
            <td>
              {canCancel(order) && (
                <button onClick={() => onCancel(order.orderId)}>
                  撤单
                </button>
              )}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
};

function getFillPercentage(order: Order): number {
  const filled = parseFloat(order.filledQuantity);
  const total = parseFloat(order.quantity);
  return total > 0 ? (filled / total) * 100 : 0;
}

function canCancel(order: Order): boolean {
  return order.status === 'NEW' || order.status === 'PARTIALLY_FILLED';
}

function OrderStatusBadge({ status }: { status: OrderStatus }) {
  const colors = {
    NEW: 'blue',
    PARTIALLY_FILLED: 'orange',
    FILLED: 'green',
    CANCELED: 'gray',
    REJECTED: 'red',
    EXPIRED: 'gray'
  };
  
  return (
    <span className={`badge ${colors[status]}`}>
      {status === 'PARTIALLY_FILLED' ? '部分成交' :
       status === 'FILLED' ? '完全成交' :
       status === 'CANCELED' ? '已撤单' :
       status === 'REJECTED' ? '已拒绝' :
       status === 'EXPIRED' ? '已过期' : '待成交'}
    </span>
  );
}
```

---

## 5. 最佳实践

### 5.1 性能优化

1. **虚拟列表**: 订单量大时使用虚拟滚动
2. **防抖更新**: 高频推送时合并 UI 更新
3. **增量渲染**: 只更新变化的行

### 5.2 错误处理

1. **超时处理**: 下单请求超时后主动查询订单状态
2. **状态回滚**: 本地订单创建后下单失败，需要移除
3. **重复检测**: 根据 clientOrderId 避免重复下单

### 5.3 测试建议

1. **单元测试**: 测试各种订单状态转换
2. **集成测试**: 模拟 WebSocket 消息
3. **E2E测试**: 完整下单→成交流程

---

*文档结束*
