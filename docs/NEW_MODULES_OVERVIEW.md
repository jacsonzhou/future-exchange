# 新增 P0 模块概览

本文档汇总了5个P0级新增模块的设计与实现。

## 模块清单

| 模块名 | 端口 | 数据库 | 核心功能 |
|--------|------|--------|----------|
| funding-rate-core | 8088 | exchange_funding | 资金费率计算与结算 |
| tp-sl-core | 8089 | exchange_tpsl | 止盈止损订单管理 |
| margin-mode-core | 8090 | exchange_margin | 全仓/逐仓模式管理 |
| adl-core | 8091 | exchange_adl | ADL自动减仓 |
| market-maker-core | 8092 | exchange_mm | 做市商接口 |

---

## 1. 资金费率结算 (funding-rate-core)

### 功能说明
永续合约资金费率结算系统，每8小时对持仓用户进行资金费用收付。

### 核心流程
```
指数价格 → 计算溢价指数 → 计算资金费率 → 结算资金费用 → 更新余额
```

### API 列表
- `GET /api/v1/funding-rate/history` - 查询历史费率
- `GET /api/v1/funding-rate/estimate` - 查询预估费率
- `GET /api/v1/funding-rate/user-fees` - 查询用户资金费用

### Kafka Topics
- `funding-rate-calc` - 费率计算完成事件
- `funding-settlement` - 资金费用结算事件
- `index-price-update` - 指数价格更新

---

## 2. 止盈止损订单 (tp-sl-core)

### 功能说明
条件订单系统，当价格达到预设触发价时自动执行平仓。

### 支持类型
- TP (Take Profit) - 止盈单
- SL (Stop Loss) - 止损单
- TPSL (组合订单) - 同时设置止盈止损
- Trailing Stop - 移动止损

### API 列表
- `POST /api/v1/tp-sl/create` - 创建TP/SL
- `POST /api/v1/tp-sl/modify` - 修改TP/SL
- `POST /api/v1/tp-sl/cancel` - 撤销TP/SL
- `GET /api/v1/tp-sl/list` - 查询TP/SL列表

### Kafka Topics
- `mark-price-update` - 标记价格更新（触发判断）
- `position-closed` - 持仓平仓事件
- `tp-sl-triggered` - TP/SL触发事件

---

## 3. 全仓/逐仓模式 (margin-mode-core)

### 功能说明
保证金模式管理系统，支持全仓和逐仓两种模式。

### 模式对比
| 特性 | 全仓 | 逐仓 |
|------|------|------|
| 保证金来源 | 账户全部资金 | 独立分配 |
| 爆仓影响 | 全部仓位 | 仅该仓位 |
| 风险 | 共享 | 隔离 |

### API 列表
- `POST /api/v1/margin/switch-mode` - 切换模式
- `POST /api/v1/margin/change-leverage` - 修改杠杆
- `POST /api/v1/margin/add-isolated` - 追加保证金
- `POST /api/v1/margin/remove-isolated` - 减少保证金

### Kafka Topics
- `margin-mode-changed` - 模式切换事件
- `leverage-changed` - 杠杆修改事件
- `margin-added` - 保证金变动事件

---

## 4. ADL自动减仓 (adl-core)

### 功能说明
当保险基金不足以覆盖穿仓损失时，自动平仓盈利用户仓位分摊损失。

### ADL优先级
按盈利比例和有效杠杆排序，盈利越高、杠杆越大越先被选中。

### API 列表
- `GET /api/v1/adl/ranking` - 查询ADL排名
- `GET /api/v1/adl/history` - 查询ADL历史
- `GET /api/v1/adl/insurance-fund` - 查询保险基金

### Kafka Topics
- `liquidation-completed` - 强平完成事件
- `adl-triggered` - ADL触发事件
- `adl-executed` - ADL执行完成事件

---

## 5. 做市商接口 (market-maker-core)

### 功能说明
专业做市商服务，提供批量订单、费率优惠、报价质量监控等功能。

### 做市商等级
| 等级 | 月交易量 | Maker返佣 | API限制 |
|------|----------|-----------|---------|
| 1 | > 1000 BTC | 0.02% | 1000/s |
| 2 | > 5000 BTC | 0.03% | 2000/s |
| 3 | > 20000 BTC | 0.05% | 5000/s |

### API 列表
- `POST /api/v1/mm/batch-order/create` - 批量下单
- `POST /api/v1/mm/batch-order/cancel` - 批量撤单
- `POST /api/v1/mm/order/cancel-all` - 一键撤单
- `GET /api/v1/mm/performance` - 查询考核指标

### Kafka Topics
- `mm-batch-order` - 批量订单
- `mm-fee-rebate` - 费率返佣事件
- `mm-performance-update` - 考核指标更新

---

## 数据库初始化

```bash
# 创建数据库
mysql -u root -p

CREATE DATABASE exchange_funding DEFAULT CHARSET utf8mb4;
CREATE DATABASE exchange_tpsl DEFAULT CHARSET utf8mb4;
CREATE DATABASE exchange_margin DEFAULT CHARSET utf8mb4;
CREATE DATABASE exchange_adl DEFAULT CHARSET utf8mb4;
CREATE DATABASE exchange_mm DEFAULT CHARSET utf8mb4;

# 导入表结构
mysql -u root -p exchange_funding < sql/05_funding_rate_schema.sql
mysql -u root -p exchange_tpsl < sql/06_tp_sl_schema.sql
mysql -u root -p exchange_margin < sql/07_margin_mode_schema.sql
mysql -u root -p exchange_adl < sql/08_adl_schema.sql
mysql -u root -p exchange_mm < sql/09_market_maker_schema.sql
```

## Kafka Topic 初始化

```bash
# 查看 docs/kafka_topics_new.md 中的创建脚本
# 或运行:
./init_kafka_new.sh
```

## 服务启动顺序

```bash
# 1. 基础设施
# MySQL, Redis, Kafka, Nacos

# 2. 核心服务（先启动原有的）
# match-engine-core, ledger-core, oms-core

# 3. 新增服务
java -jar funding-rate-core/target/funding-rate-core-1.0.0-SNAPSHOT.jar
java -jar tp-sl-core/target/tp-sl-core-1.0.0-SNAPSHOT.jar
java -jar margin-mode-core/target/margin-mode-core-1.0.0-SNAPSHOT.jar
java -jar adl-core/target/adl-core-1.0.0-SNAPSHOT.jar
java -jar market-maker-core/target/market-maker-core-1.0.0-SNAPSHOT.jar
```

## 端口分配汇总

| 服务 | 端口 |
|------|------|
| api-gateway | 8080 |
| oms-core | 8081 |
| hard-risk-core | 8082 |
| match-engine-core | 8083 |
| ledger-core | 8084 |
| snapshot-account-core | 8085 |
| position-snapshot-core | 8086 |
| replay-core | 8087 |
| **funding-rate-core** | **8088** |
| **tp-sl-core** | **8089** |
| **margin-mode-core** | **8090** |
| **adl-core** | **8091** |
| **market-maker-core** | **8092** |
