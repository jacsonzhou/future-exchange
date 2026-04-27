# WebSocket快照测试指南

## 快速开始

### 1. 启动所有服务

```bash
# 启动基础设施
docker-compose up -d redis kafka mysql

# 启动数据源
docker-compose up -d binance-data-source

# 启动市场服务
docker-compose up -d market-price-core

# 启动推送服务
docker-compose up -d public-push-core

# 等待30秒让服务完全启动
sleep 30
```

### 2. 运行自动化测试

```bash
# 测试默认交易对（BTCUSDT）
./scripts/test_snapshot_freshness.sh

# 测试指定交易对
./scripts/test_snapshot_freshness.sh ETHUSDT
```

### 3. 预期输出

```
==========================================
  WebSocket快照数据新鲜度测试
==========================================
Symbol: BTCUSDT
Redis: localhost:6379
WebSocket: ws://localhost:8096/ws/market

----------------------------------------
1. 检查Redis快照数据
----------------------------------------
检查 market:snapshot:depth:BTCUSDT ...
✓ PASS: 盘口快照存在且新鲜 (age: 234ms)

检查 binance:trade:BTCUSDT ...
✓ PASS: Trade列表存在，包含 100 条记录
✓ PASS: 最新Trade新鲜 (age: 456ms)

检查 market:snapshot:kline:history:BTCUSDT:1m ...
✓ PASS: K线历史存在，包含 50 根K线
✓ PASS: 最新K线新鲜 (age: 12345ms, ~12s)

检查 market:snapshot:kline:BTCUSDT:1m ...
✓ PASS: 当前K线快照新鲜 (age: 3456ms, ~3s)

----------------------------------------
2. 测试WebSocket订阅快照
----------------------------------------
使用websocat测试WebSocket连接...
发送订阅请求...

收到响应（前5条消息）：
{"result":"connected","serverTime":1709876543210}
{"result":"subscribed","id":1,"data":{"subscribed":["depth.BTCUSDT","trade.BTCUSDT","kline.BTCUSDT.1m"]}}
{"stream":"trade.BTCUSDT","data":{...},"snapshot":true}
{"stream":"kline.BTCUSDT.1m","data":{...},"snapshot":true}
{"stream":"depth.BTCUSDT","data":{...},"snapshot":true}

✓ PASS: 收到快照消息（包含 snapshot:true 标记）
✓ PASS: Trade快照包含多条记录: 100 条
✓ PASS: K线快照包含历史: 50 根

==========================================
  测试完成
==========================================
```

## 手动测试

### 使用websocat测试

```bash
# 安装websocat
brew install websocat  # macOS
# 或
cargo install websocat  # Linux

# 连接WebSocket
websocat ws://localhost:8096/ws/market

# 订阅多个频道
{"method":"SUBSCRIBE","params":["depth.BTCUSDT","trade.BTCUSDT","kline.BTCUSDT.1m"],"id":1}

# 预期收到：
# 1. 连接确认
{"result":"connected","serverTime":1709876543210}

# 2. 订阅确认
{"result":"subscribed","id":1,"data":{"subscribed":[...]}}

# 3. Trade快照（100条）
{
  "stream":"trade.BTCUSDT",
  "data":{
    "e":"tradeSnapshot",
    "s":"BTCUSDT",
    "trades":[/* 100条成交 */],
    "count":100
  },
  "snapshot":true
}

# 4. K线快照（50根历史+1根当前）
{
  "stream":"kline.BTCUSDT.1m",
  "data":{
    "e":"klineSnapshot",
    "s":"BTCUSDT",
    "i":"1m",
    "current":{/* 当前K线 */},
    "history":[/* 50根历史K线 */],
    "historyCount":50
  },
  "snapshot":true
}

# 5. 盘口快照
{
  "stream":"depth.BTCUSDT",
  "data":{
    "e":"depthUpdate",
    "s":"BTCUSDT",
    "bids":[[/* 买盘20档 */]],
    "asks":[[/* 卖盘20档 */]]
  },
  "snapshot":true
}

# 6. 之后是实时推送...
```

### 使用Redis CLI检查

```bash
# 检查Trade列表
redis-cli LRANGE binance:trade:BTCUSDT 0 -1 | jq '.'
# 应返回100条

# 检查K线历史
redis-cli LRANGE market:snapshot:kline:history:BTCUSDT:1m 0 -1 | jq '.'
# 应返回最多50条

# 检查当前K线
redis-cli GET market:snapshot:kline:BTCUSDT:1m | jq '.'
# 应返回当前正在形成的K线

# 检查盘口
redis-cli GET market:snapshot:depth:BTCUSDT | jq '.'
# 应返回最新盘口
```

## 故障排查

### 问题1：Trade列表为空

**原因**：binance-data-source未运行或未推送数据

**解决**：
```bash
# 检查服务状态
docker-compose ps binance-data-source

# 查看日志
docker-compose logs -f binance-data-source

# 重启服务
docker-compose restart binance-data-source
```

### 问题2：K线历史为空

**原因**：K线尚未关闭（未到周期结束）

**解决**：
- 等待1分钟（1m K线）
- 或检查更长周期：`redis-cli LRANGE market:snapshot:kline:history:BTCUSDT:1h 0 -1`

### 问题3：快照时间过期

**原因**：数据源断开或延迟

**解决**：
```bash
# 检查binance-data-source日志
docker-compose logs -f binance-data-source | grep ERROR

# 检查网络连接
curl -I https://fapi.binance.com/fapi/v1/ping

# 重启数据源
docker-compose restart binance-data-source
```

### 问题4：WebSocket无法连接

**原因**：public-push-core未启动

**解决**：
```bash
# 检查服务状态
docker-compose ps public-push-core

# 查看端口
netstat -an | grep 8096

# 查看日志
docker-compose logs -f public-push-core

# 重启服务
docker-compose restart public-push-core
```

## 性能测试

### Redis内存使用

```bash
# 检查总内存
redis-cli INFO memory | grep used_memory_human

# 检查K线历史内存（约50KB/symbol）
redis-cli --scan --pattern "market:snapshot:kline:history:*" | wc -l
# 预期：symbol数量 × 14周期

# 检查Trade列表内存（约20KB/symbol）
redis-cli --scan --pattern "binance:trade:*" | wc -l
```

### WebSocket订阅延迟

```bash
# 使用时间戳对比
time websocat ws://localhost:8096/ws/market <<< '{"method":"SUBSCRIBE","params":["depth.BTCUSDT"],"id":1}'

# 预期：< 100ms
```

## 持续监控

### 关键日志

```bash
# 查看快照新鲜度告警
tail -f logs/public-push.log | grep "Stale.*snapshot"

# 查看快照缺失告警
tail -f logs/public-push.log | grep "snapshot not found"

# 查看K线历史更新
tail -f logs/market-price-core.log | grep "Updated kline history"
```

### Prometheus指标

```promql
# 快照过期率
rate(websocket_snapshot_stale_total[5m])

# 快照缺失率
rate(websocket_snapshot_miss_total[5m])

# K线历史大小
redis_list_length{key="market:snapshot:kline:history:BTCUSDT:1m"}
```

## 验收标准

- [ ] Trade快照包含100条记录
- [ ] K线快照包含50根历史+1根当前
- [ ] 所有快照时间戳在允许范围内
- [ ] WebSocket订阅延迟 < 100ms
- [ ] Redis内存增长 < 100MB
- [ ] 无ERROR日志
- [ ] 无频繁的WARN日志（< 1条/分钟）
