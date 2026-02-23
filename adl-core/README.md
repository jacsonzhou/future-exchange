# ADL-Core 自动减仓模块

## 模块概述

ADL（Auto-Deleveraging，自动减仓）是合约交易系统中当强平无法完全处理时，按照ADL排名自动选择对手方仓位进行减仓的机制。

## 核心职责

1. **ADL排名队列维护**
   - 实时计算各用户的ADL排名
   - 按盈利比例和杠杆倍数排序
   - 每symbol独立队列

2. **强平事件处理**
   - 消费强平服务发布的强平事件
   - 判断是否需要触发ADL
   - 执行自动减仓

3. **保险基金管理**
   - 管理保险基金余额
   - 记录保险基金收支
   - 提供资金保障

## 端口信息

- **服务端口**: 8091
- **数据库**: exchange_adl
- **Redis DB**: 5

## 核心表结构

### 1. adl_ranking_queue - ADL排名队列
- 维护每个用户的ADL排名信息
- 按ADL Score排序，确定减仓优先级

### 2. adl_execution - ADL执行记录
- 记录每次ADL执行的详细信息
- 用于审计、对账和纠纷处理

### 3. insurance_fund - 保险基金
- 管理保险基金余额
- 记录累计收支

## ADL排名算法

```
ADL Score = (PnL Ratio) × (Effective Leverage) × 10000

其中：
- PnL Ratio = unrealizedPnl / marginBalance
- Effective Leverage = positionValue / marginBalance
```

**排名规则**（对标主流交易所）：
1. 盈利越多，排名越靠前（优先被ADL）
2. 杠杆越高，排名越靠前
3. 同分情况下按开仓时间排序（先开仓者优先）

## API接口

### ADL排名查询
- `GET /api/adl/ranking/{userId}?symbol={symbol}` - 查询用户ADL排名
- `GET /api/adl/ranking/queue/{symbol}?limit={limit}` - 获取排名队列
- `GET /api/adl/ranking/candidates/{symbol}?side={side}` - 获取ADL候选

### ADL执行历史
- `GET /api/adl/execution/{adlExecutionId}` - 查询执行详情
- `GET /api/adl/history/{userId}?startTime={startTime}&endTime={endTime}` - 查询用户历史
- `GET /api/adl/statistics/{symbol}` - 查询统计信息

### 保险基金
- `GET /api/adl/insurance-fund/{symbol}?currency={currency}` - 查询保险基金
- `GET /api/adl/insurance-fund/{symbol}/status` - 查询基金状态

### 管理接口
- `POST /api/adl/admin/refresh-ranking/{symbol}` - 手动刷新排名
- `POST /api/adl/admin/calculate-score` - 计算ADL得分

## Kafka Topic

### 消费
- `liquidation-event` - 强平完成事件
- `liquidation-failed` - 强平失败事件
- `insurance-fund-alert` - 保险基金告警

### 生产
- `adl-event-{symbol}` - ADL执行事件

## 初始化

```bash
# 创建数据库
mysql -u root -p < sql/adl_schema.sql

# 启动服务
cd adl-core
mvn spring-boot:run
```

## 配置说明

详见 `application.yml`：
- ADL排名刷新频率：5秒
- 保险基金阈值：安全100万，警告50万，危险10万
- Redis缓存TTL：30分钟

## 对标交易所

- Binance
- OKX
- Bybit
