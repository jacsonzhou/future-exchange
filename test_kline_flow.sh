#!/bin/bash
# K线数据流诊断脚本

echo "=========================================="
echo "K线数据流诊断工具"
echo "=========================================="
echo ""

# 检查服务状态
echo "1. 检查服务状态..."
echo "   - market-price-core (8095):"
if lsof -i :8095 >/dev/null 2>&1; then
    echo "     ✓ 运行中"
else
    echo "     ✗ 未运行"
fi

echo "   - public-push-core (8096):"
if lsof -i :8096 >/dev/null 2>&1; then
    echo "     ✓ 运行中"
else
    echo "     ✗ 未运行"
fi

echo ""
echo "2. 检查Kafka Topic..."
kafka-topics --list --bootstrap-server localhost:9092 2>/dev/null | grep "market.kline.BTCUSDT.1m" && echo "   ✓ Topic存在" || echo "   ✗ Topic不存在"

echo ""
echo "3. 检查K线消息生产..."
echo "   正在读取5条K线消息（5秒超时）..."
kafka-console-consumer --bootstrap-server localhost:9092 --topic market.kline.BTCUSDT.1m --from-beginning --max-messages 5 --timeout-ms 5000 2>/dev/null
if [ $? -eq 0 ]; then
    echo "   ✓ K线消息正常生产"
else
    echo "   ✗ 未读取到K线消息"
fi

echo ""
echo "4. 测试WebSocket连接..."
echo "   请在浏览器中打开:"
echo "   - trading-test.html (完整交易系统)"
echo "   - trading-chart.html (独立K线图表)"
echo ""
echo "   检查浏览器控制台日志，确认:"
echo "   1. WebSocket连接成功"
echo "   2. 订阅kline.BTCUSDT.1m成功"
echo "   3. 收到kline消息"
echo ""

# 检查消费组
echo "5. 检查Kafka消费组..."
kafka-consumer-groups --bootstrap-server localhost:9092 --list 2>/dev/null | grep -E "(market-price|public-push)" || echo "   未找到消费组"

echo ""
echo "=========================================="
echo "诊断完成"
echo "=========================================="
