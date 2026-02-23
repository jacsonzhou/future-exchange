# 币安数据源系统产品需求文档 (PRD)

> **版本**: v1.0.0  
> **状态**: Production Grade  
> **最后更新**: 2026-02-19  
> **作者**: Exchange Product Team

---

## 一、需求背景

### 1.1 业务目标

作为合约交易所，我们需要为用户提供高质量的行情数据参考。币安作为行业标杆，其行情数据具有高度公信力。通过接入币安实时盘口和成交数据，我们可以：

1. **为用户提供市场参考**：让用户看到币安的真实盘口深度和成交情况
2. **做市商策略支持**：为内部做市商提供外部市场数据参考
3. **套利信号监控**：监控与币安的价格差异，发现套利机会
4. **风险控制参考**：将币安价格作为异常波动检测的基准

### 1.2 用户画像

| 用户类型 | 需求场景 | 核心诉求 |
|---------|---------|---------|
| **普通交易者** | 查看币安实时行情作为交易参考 | 数据实时、准确、易读 |
| **量化交易员** | 接入币安数据进行策略分析 | 低延迟、完整深度、可订阅 |
| **做市商** | 参考币安盘口调整报价策略 | 高频更新、BBO优先 |
| **风控人员** | 监控与币安的价格偏离 | 多交易对、告警机制 |

---

## 二、功能需求

### 2.1 功能架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            Binance Exchange                                  │
│                         (data-stream.binance.com)                           │
│                                                                              │
│   ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐         │
│   │  @depth@{symbol}│    │  @trade@{symbol}│    │  @ticker@{symbol│         │
│   │  (L2 OrderBook) │    │  (Real-time     │    │  (24h Stats)    │         │
│   │                 │    │   Trades)       │    │                 │         │
│   └────────┬────────┘    └────────┬────────┘    └────────┬────────┘         │
└────────────┼──────────────────────┼──────────────────────┼──────────────────┘
             │                      │                      │
             │    WebSocket Stream  │                      │
             ▼                      ▼                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        币安数据源服务 (8099)                                 │
│                    Binance Data Source Service                               │
│                                                                              │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐          │
│  │ WebSocket Client │  │  Data Transformer│  │ Kafka Publisher  │          │
│  │   (Connection    │→ │ (Binance →       │→ │  (Internal       │          │
│  │    Manager)      │  │  Internal Format)│  │   Market Events) │          │
│  └──────────────────┘  └──────────────────┘  └──────────────────┘          │
└────────────────────────┬─────────────────────────────────────────────────────┘
                         │
                         ▼ Kafka Topics
┌─────────────────────────────────────────────────────────────────────────────┐
│                    内部行情系统 (Existing Infrastructure)                     │
│                                                                              │
│   market.depth.BTCUSDT    market.trade.BTCUSDT    market.ticker.BTCUSDT     │
│   market.depth.ETHUSDT    market.trade.ETHUSDT    market.ticker.ETHUSDT     │
│        ...                     ...                      ...                 │
│                                                                              │
│   → Public Push System (WebSocket to clients)                               │
│   → Market Data Engine (Analytics & Storage)                                │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 核心功能

#### 2.2.1 深度数据获取 (OrderBook L2)

**功能描述**：
- 通过WebSocket实时订阅币安L2深度数据
- 支持5档、10档、20档深度可选
- 自动处理快照+增量更新模式
- 序号校验确保数据完整性

**数据规格**：
| 字段 | 类型 | 说明 |
|-----|------|------|
| symbol | String | 交易对，如 BTCUSDT |
| bids | [[price,qty],...] | 买盘深度，价格降序 |
| asks | [[price,qty],...] | 卖盘深度，价格升序 |
| lastUpdateId | Long | 最后更新序号 |
| timestamp | Long | 数据时间戳 |

**更新频率**：
- 默认100ms推送一次（币安原生）
- 系统可配置批量聚合窗口（10-500ms）

#### 2.2.2 实时成交数据 (Trade Stream)

**功能描述**：
- 实时获取币安成交记录
- 支持聚合成交（AggTrade）和逐笔成交（Trade）
- 维护24小时成交统计（Ticker）

**数据规格**：
| 字段 | 类型 | 说明 |
|-----|------|------|
| tradeId | Long | 成交ID |
| price | Long | 成交价格（8位精度）|
| quantity | Long | 成交数量（8位精度）|
| isBuyerMaker | Boolean | true=买方挂单方（主动卖）|
| timestamp | Long | 成交时间 |

**更新频率**：
- 实时推送，无延迟

#### 2.2.3 连接管理

**功能描述**：
- 多交易对聚合订阅（单连接最大支持100个streams）
- 自动重连机制（指数退避）
- 心跳检测与保活
- 连接状态监控与告警

**重连策略**：
```
第1次重连: 1秒后
第2次重连: 2秒后
第3次重连: 4秒后
第4次重连: 8秒后
第5次及以上: 30秒后（固定）
```

---

## 三、非功能需求

### 3.1 性能指标

| 指标 | 目标值 | 说明 |
|------|--------|------|
| 端到端延迟 | < 200ms | 币安→内部Kafka |
| 深度更新延迟 | < 150ms | 币安→内部系统 |
| 成交延迟 | < 100ms | 币安→内部系统 |
| 重连时间 | < 5s | 检测到断开后 |
| 内存占用 | < 2GB | 单实例，100交易对 |

### 3.2 可靠性

- **数据完整性**：序号检查，发现Gap自动重连并重载快照
- **连接稳定性**：99.9%连接可用性
- **故障隔离**：单交易对异常不影响其他交易对

### 3.3 可扩展性

- 支持水平扩展（多实例分担不同交易对）
- 交易对配置热更新（无需重启）
- 动态订阅/取消订阅

---

## 四、接口设计

### 4.1 内部API

#### 4.1.1 获取币安深度快照

```
GET /api/binance/depth/{symbol}

Response:
{
    "symbol": "BTCUSDT",
    "timestamp": 1708310400000,
    "lastUpdateId": 123456789,
    "bids": [["50000.00", "1.5"], ["49999.50", "2.0"], ...],
    "asks": [["50000.50", "1.2"], ["50001.00", "3.0"], ...]
}
```

#### 4.1.2 获取连接状态

```
GET /api/binance/status

Response:
{
    "connected": true,
    "subscribedSymbols": ["BTCUSDT", "ETHUSDT"],
    "uptimeSeconds": 86400,
    "messagesReceived": 15000000,
    "reconnectCount": 2
}
```

### 4.2 配置管理

```yaml
binance:
  datasource:
    # WebSocket配置
    ws-url: "wss://data-stream.binance.com/ws"
    # 或集群模式
    # ws-url: "wss://data-stream.binance.com/stream?streams=btcusdt@depth/ethusdt@depth"
    
    # 订阅配置
    symbols:
      - BTCUSDT
      - ETHUSDT
      - BNBUSDT
    
    # 深度档位
    depth-levels: 20  # 5, 10, 20
    
    # 聚合配置
    batch-window-ms: 100  # 批量聚合窗口
    
    # 重连配置
    reconnect:
      max-attempts: 10
      base-delay-ms: 1000
      max-delay-ms: 30000
    
    # 心跳配置
    heartbeat:
      enabled: true
      interval-sec: 30

kafka:
  enabled: true
  topics:
    depth: "market.depth.{}"      # {} = symbol
    trade: "market.trade.{}"
    ticker: "market.ticker.{}"
```

---

## 五、数据处理流程

### 5.1 深度数据处理（含序号检查与重建机制）

```
1. 建立WebSocket连接
        ↓
2. 发送订阅消息:
   {"method": "SUBSCRIBE", "params": ["btcusdt@depth@100ms"], "id": 1}
        ↓
3. 接收快照消息 (firstUpdateId ~ lastUpdateId)
        ↓
4. 初始化本地OrderBook
        ↓
5. 接收增量更新消息
        ↓
6. ⚠️ 序号检查（关键）:
   ┌─────────────────────────────────────────────────────────┐
   │ 当前 lastUpdateId = L                                    │
   │ 新消息 U (firstUpdateId), u (lastUpdateId)               │
   │                                                          │
   │ Case 1: U <= L+1 && u >= L+1  ✅ 正常更新               │
   │   → 应用增量到本地OrderBook                              │
   │                                                          │
   │ Case 2: u < L+1  ⚠️ 旧消息                              │
   │   → 丢弃（已处理过）                                      │
   │                                                          │
   │ Case 3: U > L+1  ❌ 序号Gap（数据丢失）                  │
   │   → 标记OrderBook为STALE状态                             │
   │   → 触发快照重建流程（见5.1.1）                          │
   │   → 记录告警日志                                         │
   └─────────────────────────────────────────────────────────┘
        ↓
7. 更新本地OrderBook
        ↓
8. 数据质量校验（见5.1.2）
        ↓
9. 转换为内部格式 → 发布到Kafka
```

#### 5.1.1 订单簿重建流程

```
触发条件：
- 序号Gap检测到
- 心跳超时后重连
- 手动触发重建

重建步骤：
1. 标记OrderBook状态 = REBUILDING
        ↓
2. 暂停深度数据发布（避免发布脏数据）
        ↓
3. 调用REST API获取快照:
   GET https://api.binance.com/api/v3/depth?symbol=BTCUSDT&limit=1000
        ↓
4. 解析快照并初始化OrderBook
        ↓
5. 缓存积压的增量消息（在重建期间收到的）
        ↓
6. 应用积压消息（序号 > snapshot.lastUpdateId）
        ↓
7. 标记OrderBook状态 = ACTIVE
        ↓
8. 恢复深度数据发布
        ↓
9. 记录重建成功日志
```

**REST API快照格式**：
```json
{
  "lastUpdateId": 123456789,
  "bids": [["50000.00", "1.5"], ...],
  "asks": [["50001.00", "2.0"], ...]
}
```

#### 5.1.2 数据质量校验

**价格合理性校验**：
```
1. 检查 bid < ask（买价必须低于卖价）
2. 检查价格变化幅度 < 20%（相比上一次）
3. 检查深度档位数量合理（5-1000档）
4. 检查价格精度（非负数、符合精度要求）
```

**数据新鲜度校验**：
```
1. eventTime 与本地时间差 < 5秒
2. 同一symbol深度更新频率 >= 10次/秒
3. 超过5秒未收到更新 → 触发告警
```

### 5.2 成交数据处理

```
1. 订阅: {"method": "SUBSCRIBE", "params": ["btcusdt@trade"], "id": 2}
        ↓
2. 实时接收成交消息
        ↓
3. 数据校验:
   - tradeId 递增检查（可能有Gap，仅记录告警）
   - 价格合理性检查（与最新深度比对）
   - 成交量合理性检查（避免异常大单）
        ↓
4. 转换为内部Trade格式
        ↓
5. 更新本地Ticker统计（滑动窗口）
        ↓
6. 发布到Kafka
```

---

## 六、数据质量监控与告警

### 6.1 核心监控指标

| 指标类别 | 指标名称 | 目标值 | 告警阈值 | 说明 |
|---------|---------|--------|---------|------|
| **连接健康** | 连接状态 | connected | disconnected > 10s | WebSocket连接状态 |
| | 重连次数 | < 5次/小时 | > 10次/小时 | 频繁重连可能表示网络问题 |
| | 心跳延迟 | < 100ms | > 1000ms | pong响应时间 |
| **数据质量** | 深度更新频率 | 10次/秒 | < 5次/秒 | 币安标准100ms推送 |
| | 序号Gap次数 | 0次 | > 1次/小时 | 序号跳跃次数 |
| | 订单簿重建次数 | < 1次/天 | > 5次/小时 | 频繁重建表示数据质量问题 |
| | 价格异常次数 | 0次 | > 10次/小时 | bid>=ask 或价格跳变>20% |
| **延迟监控** | 端到端延迟 | < 200ms | > 500ms | 币安eventTime → Kafka发布 |
| | 处理延迟 | < 50ms | > 100ms | 消息接收 → 解析完成 |
| | 发布延迟 | < 50ms | > 100ms | 解析完成 → Kafka发布 |
| **吞吐量** | 消息接收速率 | 100-500/秒 | < 50/秒 | 每个symbol |
| | Kafka发布速率 | 100-500/秒 | < 50/秒 | 发布成功率 |
| **资源使用** | 内存占用 | < 1GB | > 2GB | JVM堆内存 |
| | CPU使用率 | < 30% | > 70% | 平均CPU |
| | GC频率 | < 10次/分钟 | > 50次/分钟 | FullGC频率 |

### 6.2 告警规则

**P0 告警（立即处理）**：
- WebSocket断开超过30秒
- 订单簿重建失败
- 价格异常率 > 1%
- 端到端延迟 > 1秒

**P1 告警（1小时内处理）**：
- 序号Gap > 5次/小时
- 重连次数 > 10次/小时
- 深度更新频率 < 5次/秒持续10秒

**P2 告警（24小时内处理）**：
- 内存占用 > 1.5GB
- CPU使用率持续 > 50%
- Kafka发布延迟 > 200ms

### 6.3 监控数据采集

**指标暴露方式**：
- Prometheus Metrics（`/actuator/prometheus`）
- JMX MBean
- 自定义日志采集

**核心Metrics**：
```
# 连接状态
binance_ws_connected{symbol="BTCUSDT"} 1

# 消息接收速率
binance_messages_received_total{symbol="BTCUSDT"} 123456
binance_messages_received_rate{symbol="BTCUSDT"} 125.5

# 延迟分布
binance_latency_ms{symbol="BTCUSDT",quantile="0.5"} 45
binance_latency_ms{symbol="BTCUSDT",quantile="0.99"} 180

# 数据质量
binance_sequence_gap_total{symbol="BTCUSDT"} 0
binance_orderbook_rebuild_total{symbol="BTCUSDT"} 1
binance_price_anomaly_total{symbol="BTCUSDT"} 0

# 订单簿状态
binance_orderbook_status{symbol="BTCUSDT",status="ACTIVE"} 1
binance_orderbook_depth{symbol="BTCUSDT",side="bid"} 20
binance_orderbook_depth{symbol="BTCUSDT",side="ask"} 20
```

---

## 七、与内部系统集成规范

### 7.1 数据流向图

```
┌──────────────────────────────────────────────────────────────┐
│                      Binance Exchange                         │
│                   (外部数据源，参考行情)                       │
└────────────────────────┬─────────────────────────────────────┘
                         │ WebSocket
                         ▼
┌──────────────────────────────────────────────────────────────┐
│              Binance Data Source (8099)                       │
│              本服务 - 币安数据适配器                           │
│                                                               │
│  - 订单簿序号检查与重建                                        │
│  - 数据质量监控                                                │
│  - 格式转换（币安 → 内部标准格式）                             │
└────────────────────────┬─────────────────────────────────────┘
                         │ Kafka Topics
                         │ - market.depth.{symbol}
                         │ - market.trade.{symbol}
                         │ - market.ticker.{symbol}
                         ▼
┌──────────────────────────────────────────────────────────────┐
│                    内部行情系统消费者                          │
│                                                               │
│  1. Market Service (8086)                                    │
│     - 消费币安行情作为市场参考数据                             │
│     - 与内部撮合行情对比分析                                   │
│                                                               │
│  2. Public Push System (WebSocket Gateway)                   │
│     - 向用户推送币安参考行情                                   │
│     - 标注 source=binance 以区分内部行情                       │
│                                                               │
│  3. MarkPrice Service (8089)                                 │
│     - 使用币安价格作为标记价格输入之一                         │
│     - 计算加权平均标记价格（可选）                             │
│                                                               │
│  4. Risk Monitor Service (8087)                              │
│     - 监控内部价格与币安价格偏离                               │
│     - 价格偏离 > 5% 触发告警                                  │
│                                                               │
│  5. 做市商策略系统（内部）                                     │
│     - 参考币安盘口调整报价                                     │
│     - 套利机会监控                                            │
└──────────────────────────────────────────────────────────────┘
```

### 7.2 消息格式标准

**深度消息（与内部格式兼容）**：
```json
{
  "e": "depthUpdate",
  "E": 1708310400000,
  "s": "BTCUSDT",
  "U": 123456780,
  "u": 123456789,
  "pu": 123456779,
  "b": [["5000000000000", "150000000"]],
  "a": [["5000050000000", "200000000"]],
  "source": "binance"
}
```

**关键字段说明**：
- `E`: 事件时间（本地发布时间）
- `s`: 交易对（统一大写）
- `U`/`u`: 序号范围（已校验）
- `b`/`a`: 8位精度long值（"price", "qty"字符串格式）
- `source`: 数据源标识（binance/internal）**关键设计**：添加 `source` 字段用于区分数据来源

### 7.3 Topic命名规范

| 数据类型 | Topic格式 | 示例 | 消费者 |
|---------|----------|------|-------|
| 深度 | `market.depth.{SYMBOL}` | `market.depth.BTCUSDT` | Market, Push, Risk |
| 逐笔成交 | `market.trade.{SYMBOL}` | `market.trade.BTCUSDT` | Market, Push |
| 聚合成交 | `market.aggtrade.{SYMBOL}` | `market.aggtrade.BTCUSDT` | Market, Push |
| 24h统计 | `market.ticker.{SYMBOL}` | `market.ticker.BTCUSDT` | Market, Push |

**注意事项**：
1. Symbol统一大写（BTCUSDT，不是btcusdt）
2. Topic格式与内部撮合系统完全一致
3. 消费者通过 `source` 字段区分数据来源

### 7.4 数据使用场景

#### 7.4.1 场景1：为用户提供市场参考行情

**需求**：用户在交易界面查看"币安实时行情"作为参考

**实现方案**：
```
Public Push System 消费 market.depth.BTCUSDT
  ↓
通过WebSocket推送给前端（标注 source=binance）
  ↓
前端展示：
  - 主行情：内部撮合行情
  - 参考行情：币安行情（小字显示"Binance参考"）
```

#### 7.4.2 场景2：价格偏离监控

**需求**：监控内部价格与币安的偏离度，偏离>5%触发告警

**实现方案**：
```
Risk Monitor Service 同时消费：
  - market.depth.BTCUSDT (source=binance)
  - market.depth.BTCUSDT (source=internal)
    ↓
计算价格偏离：
  deviation = abs(binance_price - internal_price) / binance_price
    ↓
偏离 > 5% → 发送告警
  - 通知运营团队
  - 可能暂停开仓（风控决策）
```

#### 7.4.3 场景3：做市商策略参考

**需求**：做市商根据币安盘口调整报价策略

**实现方案**：
```
做市商策略服务消费 market.depth.BTCUSDT (source=binance)
  ↓
分析币安盘口：
  - BBO（最优买卖价）
  - 深度分布（5档/10档）
  - 买卖价差
    ↓
调整内部报价：
  - 跟随币安价格
  - 收紧/扩大价差
  - 调整挂单量
```

### 7.5 故障降级策略

| 故障场景 | 降级方案 | 恢复策略 |
|---------|---------|---------|
| **币安WebSocket断开** | 1. 自动重连（指数退避）<br>2. 重连失败>10次，停止发布<br>3. Redis快照继续可用 | 重连成功后恢复发布 |
| **币安服务完全不可用** | 1. 停止发布币安数据<br>2. Public Push切换到仅推送内部行情<br>3. 标记价格切换到内部价格 | 币安恢复后自动接入 |
| **数据质量异常** | 1. 序号Gap→触发重建<br>2. 价格异常→过滤不发布<br>3. 延迟过高→降低优先级 | 数据恢复正常后自动恢复 |
| **Kafka消费堆积** | 1. 增加消费者并行度<br>2. 临时提高批处理大小<br>3. 紧急清理旧数据 | 堆积清除后恢复正常 |
| **内存溢出风险** | 1. 限制订单簿深度（最多100档）<br>2. 减少缓存交易对数量<br>3. 增加GC频率 | 释放内存后恢复 |

---

## 八、风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| 币安API限流 | 连接断开 | 指数退避重连，监控告警 |
| 网络抖动 | 数据延迟 | 自动重连，序号校验 |
| 数据Gap | 深度不准确 | **序号检查 + 自动重建**（新增） |
| 币安服务故障 | 数据源中断 | 多交易所备份（Phase 2）|
| 内存溢出 | 服务崩溃 | 限制缓存大小，对象复用，订单簿深度限制 |
| **订单簿数据不一致** | 影响用户决策 | **序号连续性校验 + 快照重建**（新增） |
| **价格异常数据** | 误导用户/触发错误风控 | **价格合理性校验 + 过滤机制**（新增） |
| **延迟过高** | 数据失去参考价值 | **延迟监控 + 告警 + 自动降级**（新增） |
| **币安数据使用合规** | 法律风险 | **遵守币安API使用条款，仅用于参考**（新增） |

---

## 九、上线计划

### Phase 1 (MVP) - ✅ 已完成
- [x] BTCUSDT、ETHUSDT 深度+成交数据接入
- [x] 基础重连机制（指数退避）
- [x] Kafka数据发布（兼容内部格式）
- [x] Redis快照存储
- [x] REST API查询接口

### Phase 1.5 (数据质量增强) - 🚧 待实现
**优先级：P0（必须在生产环境上线前完成）**

- [ ] **订单簿序号检查机制**
  - [ ] 实现 lastUpdateId 连续性校验
  - [ ] Gap检测与告警
  - [ ] 本地订单簿状态管理（ACTIVE/STALE/REBUILDING）

- [ ] **订单簿重建机制**
  - [ ] REST API快照获取（https://api.binance.com/api/v3/depth）
  - [ ] 自动触发重建逻辑
  - [ ] 增量消息缓存与回放
  - [ ] 重建失败重试机制

- [ ] **数据质量校验**
  - [ ] 价格合理性检查（bid < ask，变化幅度 < 20%）
  - [ ] 数据新鲜度检查（eventTime vs 本地时间）
  - [ ] 异常数据过滤与告警

- [ ] **监控指标采集**
  - [ ] Prometheus Metrics暴露
  - [ ] 延迟统计（P50/P99）
  - [ ] 序号Gap计数器
  - [ ] 重建次数统计

**验收标准**：
1. 模拟网络抖动场景，订单簿能自动重建并恢复
2. 监控面板能实时显示数据质量指标
3. 序号Gap能在1秒内检测并触发重建

### Phase 2 (功能扩展) - 📅 2周后
- [ ] 支持更多交易对配置化（10-20个主流币种）
- [ ] Ticker 24h统计本地计算（滑动窗口）
- [ ] 聚合成交（AggTrade）优化
- [ ] WebSocket订阅动态管理（热更新）

### Phase 3 (多数据源) - 📅 1个月后
- [ ] 多交易所数据源接入
  - [ ] OKX WebSocket接入
  - [ ] Coinbase WebSocket接入
  - [ ] 统一数据适配层
- [ ] 多源数据聚合与加权
- [ ] 数据源健康度评分与自动切换

### Phase 4 (高级功能) - 📅 2个月后
- [ ] 数据质量监控面板（Grafana Dashboard）
- [ ] 历史数据回放能力（用于回测）
- [ ] 数据异常自动诊断系统
- [ ] 性能优化（零拷贝、批量处理）

---

## 十、附录

### 10.1 币安WebSocket API参考

**Base URL**: `wss://data-stream.binance.com/ws`

**Stream格式**:
- 深度: `<symbol>@depth@<speed>` (speed: 100ms, 250ms, 500ms)
- 成交: `<symbol>@trade`
- 聚合成交: `<symbol>@aggTrade`
- 24h统计: `<symbol>@ticker`

**订阅消息格式**:
```json
{
  "method": "SUBSCRIBE",
  "params": [
    "btcusdt@depth@100ms",
    "btcusdt@trade",
    "ethusdt@depth@100ms"
  ],
  "id": 1
}
```

**深度快照消息**:
```json
{
  "e": "depthUpdate",
  "E": 123456789,
  "s": "BTCUSDT",
  "U": 100000000,
  "u": 100000010,
  "b": [["50000.00", "1.5"], ["49999.50", "2.0"]],
  "a": [["50000.50", "1.2"], ["50001.00", "0"]]
}
```

**字段说明**：
- `e`: 事件类型（depthUpdate）
- `E`: 事件时间（毫秒时间戳）
- `s`: 交易对
- `U`: 本次更新第一个序号
- `u`: 本次更新最后一个序号
- `b`: 买盘更新（[price, qty]，qty=0表示删除该档位）
- `a`: 卖盘更新

**成交消息**:
```json
{
  "e": "trade",
  "E": 123456789,
  "s": "BTCUSDT",
  "t": 123456,
  "p": "50000.00",
  "q": "0.5",
  "T": 123456789,
  "m": true
}
```

**字段说明**：
- `t`: 成交ID
- `p`: 成交价格
- `q`: 成交数量
- `T`: 成交时间
- `m`: 买方是否为挂单方（true=主动卖，false=主动买）

### 10.2 币安REST API参考（用于订单簿快照）

**获取深度快照**:
```
GET https://api.binance.com/api/v3/depth
参数:
  - symbol: 交易对（必填），如 BTCUSDT
  - limit: 档位数（可选），5/10/20/50/100/500/1000/5000，默认100
```

**响应示例**:
```json
{
  "lastUpdateId": 123456789,
  "bids": [
    ["50000.00", "1.5"],
    ["49999.50", "2.0"]
  ],
  "asks": [
    ["50001.00", "1.2"],
    ["50002.00", "3.0"]
  ]
}
```

**限流**：
- 权重：根据limit调整（limit=5-100: 权重1，limit=500: 权重5，limit=1000: 权重10）
- 默认限制：1200请求权重/分钟

### 10.3 订单簿维护算法伪代码

```python
class OrderBookManager:
    def __init__(self, symbol):
        self.symbol = symbol
        self.last_update_id = 0
        self.status = "INIT"  # INIT, ACTIVE, STALE, REBUILDING
        self.bids = SortedDict()  # price -> qty
        self.asks = SortedDict()  # price -> qty
        self.buffered_updates = []

    def on_depth_update(self, message):
        """处理深度更新"""
        U = message['U']  # firstUpdateId
        u = message['u']  # lastUpdateId

        if self.status == "INIT":
            # 初始化状态，先获取快照
            self.rebuild_from_snapshot()
            return

        if self.status == "REBUILDING":
            # 重建中，缓存更新
            self.buffered_updates.append(message)
            return

        # 序号检查
        if U <= self.last_update_id + 1 and u >= self.last_update_id + 1:
            # Case 1: 正常更新
            self.apply_update(message)
            self.last_update_id = u
            self.status = "ACTIVE"

        elif u < self.last_update_id + 1:
            # Case 2: 旧消息，丢弃
            log.debug(f"Discard old update: u={u}, last={self.last_update_id}")

        else:
            # Case 3: Gap detected
            log.warning(f"Sequence gap detected: U={U}, last={self.last_update_id}")
            self.status = "STALE"
            metrics.increment("orderbook.gap.count")
            self.rebuild_from_snapshot()

    def apply_update(self, message):
        """应用增量更新"""
        for price, qty in message['b']:
            if qty == 0:
                self.bids.pop(price, None)
            else:
                self.bids[price] = qty

        for price, qty in message['a']:
            if qty == 0:
                self.asks.pop(price, None)
            else:
                self.asks[price] = qty

    def rebuild_from_snapshot(self):
        """从REST API重建订单簿"""
        self.status = "REBUILDING"

        # 1. 获取快照
        snapshot = self.fetch_snapshot()

        # 2. 初始化订单簿
        self.bids = SortedDict(snapshot['bids'])
        self.asks = SortedDict(snapshot['asks'])
        self.last_update_id = snapshot['lastUpdateId']

        # 3. 应用缓存的更新（序号 > snapshot.lastUpdateId）
        for update in self.buffered_updates:
            if update['U'] <= self.last_update_id + 1:
                self.apply_update(update)
                self.last_update_id = update['u']

        self.buffered_updates.clear()
        self.status = "ACTIVE"

        log.info(f"OrderBook rebuilt: {self.symbol}, lastUpdateId={self.last_update_id}")
        metrics.increment("orderbook.rebuild.count")

    def fetch_snapshot(self):
        """调用REST API获取快照"""
        url = f"https://api.binance.com/api/v3/depth?symbol={self.symbol}&limit=1000"
        response = requests.get(url, timeout=5)
        return response.json()
```

### 10.4 性能优化建议

#### 10.4.1 内存优化
```
1. 限制订单簿深度（最多100档）
2. 使用对象池复用消息对象
3. 使用原始类型数组而非包装类
4. 及时清理过期数据
```

#### 10.4.2 延迟优化
```
1. 使用Disruptor替代Kafka异步处理（可选）
2. 批量发布Kafka消息（10-100条/批）
3. 使用零拷贝技术（Direct Buffer）
4. 减少JSON序列化次数（预序列化）
```

#### 10.4.3 吞吐量优化
```
1. 多线程处理不同交易对
2. 使用Reactive Programming（WebFlux）
3. Kafka批量发送（linger.ms=5-10）
4. Redis Pipeline批量更新
```

### 10.5 故障排查清单

| 问题 | 排查步骤 | 解决方案 |
|------|---------|---------|
| **WebSocket频繁断开** | 1. 检查网络稳定性<br>2. 检查心跳日志<br>3. 检查币安服务状态 | 调整重连参数，检查代理设置 |
| **序号Gap频繁** | 1. 检查网络延迟<br>2. 检查CPU使用率<br>3. 检查GC日志 | 增加资源，优化GC参数 |
| **深度数据不一致** | 1. 检查序号连续性<br>2. 检查重建日志<br>3. 对比REST快照 | 触发手动重建 |
| **延迟过高** | 1. 检查Kafka消费lag<br>2. 检查Redis延迟<br>3. 检查网络RTT | 增加消费者，优化批处理 |
| **内存溢出** | 1. 检查堆内存使用<br>2. 检查订单簿深度<br>3. 分析Heap Dump | 限制深度，增加堆内存 |

---

## 十一、安全与合规

### 11.1 数据使用合规

**币安API使用条款**：
- ✅ 允许：获取公开行情数据用于参考、分析
- ❌ 禁止：转售数据、声称数据来源于币安、误导性使用
- ⚠️ 限制：遵守API限流，不得滥用

**我们的使用方式**：
- 仅用于内部参考行情，不对外转售
- 明确标注数据来源（source=binance）
- 遵守限流规则，使用合理的重连策略

### 11.2 数据安全

- 传输加密：使用WSS（WebSocket over TLS）
- 无敏感信息：仅公开行情数据，无用户隐私
- 访问控制：内部服务间通过VPC隔离

### 11.3 合规声明

本服务仅用于：
1. 为用户提供市场参考行情（非交易依据）
2. 内部风控监控（价格偏离告警）
3. 做市商策略参考（内部使用）

不用于：
1. 对外转售币安数据
2. 误导用户（明确标注为"参考行情"）
3. 替代内部撮合行情

---

*文档结束*

**版本历史**:
- v1.0.0 (2026-02-19): 初版
- v1.1.0 (2026-02-19): 新增数据质量监控、订单簿管理、与内部系统集成规范
