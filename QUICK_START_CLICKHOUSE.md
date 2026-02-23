# ClickHouse K 线模块 - 快速开始

## 1. 一键启动

```bash
# 启动 ClickHouse
./scripts/start-clickhouse.sh

# 验证启动
curl http://localhost:8123/ping
```

## 2. 启动 Market Price Core

```bash
cd market-price-core
mvn spring-boot:run
```

## 3. 测试 K 线 API

```bash
# 查询 K 线数据
curl "http://localhost:8095/api/v1/klines?symbol=BTCUSDT&interval=1m&limit=10"

# 返回格式
[[
  1704067200000,      // 开盘时间
  "50000.00000000",   // 开盘价
  "51000.00000000",   // 最高价
  "49500.00000000",   // 最低价
  "50500.00000000",   // 收盘价
  "10.50000000",      // 成交量
  1704067259999,      // 收盘时间
  "525000.00000000",  // 成交额
  100,                // 成交笔数
  "5.25000000",       // 主动买入成交量
  "262500.00000000"   // 主动买入成交额
]]
```

## 4. 常用 SQL 查询

```sql
-- 查看最近 10 条 K 线
SELECT * FROM exchange_kline.kline_data 
WHERE symbol = 'BTCUSDT' AND interval = '1m'
ORDER BY open_time DESC LIMIT 10;

-- 查看表统计
SELECT 
    symbol,
    interval,
    count() as count,
    formatReadableSize(sum(data_compressed_bytes)) as size
FROM exchange_kline.kline_data
GROUP BY symbol, interval;
```

## 5. WebSocket 订阅

```javascript
const ws = new WebSocket('ws://localhost:8096/ws/market');

ws.onopen = () => {
    ws.send(JSON.stringify({
        method: 'SUBSCRIBE',
        params: ['btcusdt@kline_1m'],
        id: 1
    }));
};

ws.onmessage = (event) => {
    const data = JSON.parse(event.data);
    console.log('K 线更新:', data);
};
```

## 端口速查

| 服务 | 端口 | 说明 |
|------|------|------|
| ClickHouse HTTP | 8123 | JDBC/HTTP 接口 |
| ClickHouse TCP | 9000 | Native 协议 |
| Tabix UI | 8088 | Web 管理界面 |
| Market Price Core | 8095 | 行情数据服务 |
| Public Push | 8096 | WebSocket 推送 |

## 常见问题

**Q: 如何查看 ClickHouse 日志？**
```bash
docker-compose -f docker-compose.clickhouse.yml logs -f clickhouse-server
```

**Q: 如何重启服务？**
```bash
docker-compose -f docker-compose.clickhouse.yml restart
```

**Q: 如何清理数据？**
```bash
docker-compose -f docker-compose.clickhouse.yml down -v
```
