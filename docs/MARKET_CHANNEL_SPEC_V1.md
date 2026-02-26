# Market Channel Spec V1

## 1. Scope

本规范定义行情通道的统一规则，覆盖：

- Topic 命名规范
- 序列号规则（深度）
- WebSocket 订阅协议
- Redis 快照键规范

目标：对标主流交易所的“权威成交簿 + 外部参考簿”双通道实践，避免内部盘口与外部盘口混流。

## 2. Topic Naming

### 2.1 Internal Authoritative Feed

- Depth: `market.depth.{symbol}`
- Trade: `market.trade.{symbol}`
- AggTrade: `market.aggtrade.{symbol}`
- Kline: `market.kline.{symbol}.{interval}`
- Ticker: `market.ticker.{symbol}`

说明：

- 该通道是撮合系统成交/盘口的权威输出。
- 必须只包含内部撮合数据。

### 2.2 External Reference Feed

- Depth: `market.ext.{source}.depth.{symbol}`
- Trade: `market.ext.{source}.trade.{symbol}`
- AggTrade: `market.ext.{source}.aggtrade.{symbol}`
- Kline: `market.ext.{source}.kline.{symbol}.{interval}`
- Ticker: `market.ext.{source}.ticker.{symbol}`

示例：

- `market.ext.binance.depth.BTCUSDT`
- `market.ext.binance.kline.BTCUSDT.1m`

说明：

- `source` 推荐小写（`binance`, `okx`, `coinbase`）。
- 外部行情只做参考展示和策略输入，不可作为撮合权威盘口。

## 3. Sequence Rules (Depth)

### 3.1 Message Shape

深度增量统一字段：

- `e=depthUpdate`
- `E` 事件时间（ms）
- `s` 交易对
- `U` 本消息首序列
- `u` 本消息尾序列
- `pu` 上一尾序列（可选）
- `b` 买盘增量
- `a` 卖盘增量
- `source` 数据来源

### 3.2 Consumer Validation

对每个 `channel` 维护独立 `lastSeq`，规则：

1. 首包：
- 要求 `U > 0 && u > 0`
- 接受后设置 `lastSeq=u`

2. 后续包：
- 令 `expected=lastSeq+1`
- 若 `U > expected` => 判定 gap，触发快照重建
- 若 `U <= expected <= u` => 正常推进，更新 `lastSeq=u`
- 若 `u < expected` => 过期包，忽略

### 3.3 Snapshot + Delta

- 客户端首次订阅先收快照，再收增量。
- 检测到 gap 必须重新拉取快照并重置 `lastSeq`。

## 4. WebSocket Subscription Protocol

### 4.1 Internal Channels

- `depth.BTCUSDT`
- `depth.BTCUSDT@100ms`
- `kline.BTCUSDT.1m`
- `trade.BTCUSDT`
- `ticker.BTCUSDT`

### 4.2 External Channels

- `depth.ext.binance.BTCUSDT`
- `depth.ext.binance.BTCUSDT@100ms`
- `kline.ext.binance.BTCUSDT.1m`
- `trade.ext.binance.BTCUSDT`
- `ticker.ext.binance.BTCUSDT`

### 4.3 Request/Response

Subscribe:

```json
{
  "method": "SUBSCRIBE",
  "params": [
    "depth.BTCUSDT@100ms",
    "depth.ext.binance.BTCUSDT",
    "kline.ext.binance.BTCUSDT.1m"
  ],
  "id": 101
}
```

Push:

```json
{
  "stream": "depth.ext.binance.BTCUSDT",
  "data": {
    "e": "depthUpdate",
    "E": 1772001043671,
    "s": "BTCUSDT",
    "U": 123,
    "u": 130,
    "b": [["50000.00000000","1.20000000"]],
    "a": [["50001.00000000","0.80000000"]],
    "source": "binance"
  }
}
```

## 5. Snapshot Key Spec

### 5.1 Internal

- Depth: `market:snapshot:depth:{symbol}`
- Trade: `market:snapshot:trade:{symbol}`
- Kline: `market:snapshot:kline:{symbol}:{interval}`
- Ticker: `market:snapshot:ticker:{symbol}`

### 5.2 External

- Depth: `market:snapshot:depth:ext:{source}:{symbol}`
- Trade: `market:snapshot:trade:ext:{source}:{symbol}`
- Kline: `market:snapshot:kline:ext:{source}:{symbol}:{interval}`
- Ticker: `market:snapshot:ticker:ext:{source}:{symbol}`

说明：

- `public-push-core` 快照首包必须优先读取 `market:snapshot:*` 规范键。
- 保留 `binance:*` 等历史键仅用于兼容旧接口，不作为推送标准键。

## 6. Migration Plan

1. 新增外部通道 topic 与 WS channel（不破坏现有内部通道）。
2. 将 `binance-data-source` 输出切到 `market.ext.binance.*`。
3. `public-push-core` 增加 `ext` channel 解析与快照读取。
4. 前端逐步切换到 `depth.ext.* / kline.ext.*` 订阅。
5. 验证后禁用外部源写入内部 `market.*` 主题。

## 7. Non-goals

- 不在本规范中定义撮合核心簿（L3）结构。
- 不在本规范中定义资金费率/标记价格计算细节。
