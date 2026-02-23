# 币安数据源服务 (Binance Data Source)

> 实时接入币安行情数据，为交易所提供外部市场参考

## 功能概述

本服务连接币安WebSocket行情流，实时获取以下数据：
- **深度数据 (OrderBook L2)**：盘口买卖档位
- **实时成交**：逐笔成交记录
- **24小时统计 (Ticker)**：涨跌幅、成交量等

数据经过格式转换后，发布到Kafka供内部系统消费。

## 架构图

```
┌─────────────────┐     WebSocket      ┌──────────────────┐     Kafka      ┌─────────────────┐
│  Binance        │ ←────────────────→ │  Binance Data    │ ────────────→ │  Internal       │
│  Exchange       │   (wss://...)      │  Source Service  │  market.*     │  Market System  │
│                 │                    │  (Port 8099)     │               │                 │
└─────────────────┘                    └──────────────────┘               └─────────────────┘
                                                │
                                                │ Redis
                                                ▼
                                       ┌──────────────────┐
                                       │  Snapshot Cache  │
                                       │  (REST API)      │
                                       └──────────────────┘
```

## 快速开始

### 1. 配置

编辑 `application.yml`：

```yaml
binance:
  datasource:
    # 订阅的交易对
    symbols:
      - BTCUSDT
      - ETHUSDT
      - BNBUSDT
    
    # 深度更新频率
    depth-speed: 100ms
    
    # 启用数据类型
    trade-enabled: true
    ticker-enabled: true
```

### 2. 启动

```bash
cd binance-data-source
mvn spring-boot:run
```

或

```bash
java -jar target/binance-data-source-1.0-SNAPSHOT.jar
```

### 3. 验证

```bash
# 检查服务状态
curl http://localhost:8099/api/binance/status

# 获取深度快照
curl http://localhost:8099/api/binance/depth/BTCUSDT

# 获取最新成交
curl http://localhost:8099/api/binance/trades/BTCUSDT?limit=10

# 获取24小时统计
curl http://localhost:8099/api/binance/ticker/BTCUSDT
```

## API接口

### 服务状态
```
GET /api/binance/status

Response:
{
  "connected": true,
  "subscribedSymbols": ["BTCUSDT", "ETHUSDT"],
  "uptimeSeconds": 3600,
  "messagesReceived": 1500000,
  "reconnectCount": 0,
  "depthLevels": 20,
  "batchWindowMs": 100
}
```

### 深度快照
```
GET /api/binance/depth/{symbol}?limit=10

Response:
{
  "success": true,
  "data": {
    "e": "depthUpdate",
    "E": 1708310400000,
    "s": "BTCUSDT",
    "U": 100000000,
    "u": 100000010,
    "b": [["5000000000000", "150000000"], ...],
    "a": [["5000050000000", "200000000"], ...],
    "source": "binance"
  },
  "timestamp": 1708310400000
}
```

### 最新成交
```
GET /api/binance/trades/{symbol}?limit=20

Response:
{
  "success": true,
  "symbol": "BTCUSDT",
  "count": 20,
  "data": [...],
  "timestamp": 1708310400000
}
```

### Ticker
```
GET /api/binance/ticker/{symbol}

Response:
{
  "success": true,
  "data": {
    "e": "24hrTicker",
    "s": "BTCUSDT",
    "c": 5000000000000,
    "p": 10000000000,
    "P": 0.2,
    "v": 1500000000000,
    ...
  }
}
```

## Kafka Topic

数据发布到以下Topic：

| Topic | 说明 |
|-------|------|
| `market.depth.{symbol}` | 深度更新 |
| `market.trade.{symbol}` | 逐笔成交 |
| `market.aggtrade.{symbol}` | 聚合成交 |
| `market.ticker.{symbol}` | 24小时统计 |

## 数据格式

### 深度数据 (8位精度)
```json
{
  "e": "depthUpdate",
  "E": 1708310400000,
  "s": "BTCUSDT",
  "U": 100000000,
  "u": 100000010,
  "pu": 100000009,
  "b": [["5000000000000", "150000000"]],
  "a": [["5000050000000", "200000000"]],
  "source": "binance"
}
```

### 成交数据
```json
{
  "e": "trade",
  "E": 1708310400000,
  "s": "BTCUSDT",
  "t": 123456789,
  "p": "5000000000000",
  "q": "100000000",
  "T": 1708310400000,
  "m": true,
  "source": "binance"
}
```

## 监控指标

服务暴露以下指标：
- `binance_ws_connected`: WebSocket连接状态
- `binance_messages_received_total`: 接收消息总数
- `binance_reconnect_count`: 重连次数

## 注意事项

1. **网络环境**：国内服务器可能需要配置代理访问币安
2. **限流**：币安限制单IP最多300连接，每5分钟150次订阅
3. **精度**：所有价格和数量使用8位精度的long存储
4. **重连**：支持指数退避自动重连

## 扩展计划

- [ ] 支持更多交易所（OKX, Coinbase）
- [ ] 数据质量监控面板
- [ ] 价格偏离告警
- [ ] 历史数据存储
