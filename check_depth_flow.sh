#!/bin/bash

# 深度数据推送流程检查脚本

echo "=========================================="
echo "深度数据推送流程检查"
echo "=========================================="
echo ""

# 1. 检查服务运行状态
echo "1. 检查服务运行状态..."
echo "----------------------------------------"
services=("OMS Core:8081" "Match Engine:8083" "Market Price Core:8095" "Public Push Core:8096")
all_running=true

for service in "${services[@]}"; do
    name=$(echo $service | cut -d: -f1)
    port=$(echo $service | cut -d: -f2)
    if lsof -i :$port 2>/dev/null | grep -q LISTEN; then
        echo "✅ $name ($port) - 运行中"
    else
        echo "❌ $name ($port) - 未运行"
        all_running=false
    fi
done
echo ""

# 2. 检查 Kafka Topics
echo "2. 检查 Kafka Topics..."
echo "----------------------------------------"
if command -v kafka-topics &> /dev/null; then
    topics=$(kafka-topics --bootstrap-server localhost:9092 --list 2>/dev/null | grep -E "order-events|market.depth")
    if echo "$topics" | grep -q "order-events"; then
        echo "✅ order-events topic 存在"
    else
        echo "❌ order-events topic 不存在"
    fi
    if echo "$topics" | grep -q "market.depth.BTCUSDT"; then
        echo "✅ market.depth.BTCUSDT topic 存在"
    else
        echo "❌ market.depth.BTCUSDT topic 不存在"
    fi
else
    echo "⚠️  kafka-topics 命令不可用，跳过检查"
fi
echo ""

# 3. 检查最近订单事件
echo "3. 检查最近订单事件（OMS → Match Engine）..."
echo "----------------------------------------"
if [ -f "oms-core/logs/oms-core.log" ]; then
    recent_order=$(tail -100 oms-core/logs/oms-core.log 2>/dev/null | grep -E "Order event sent|publishOrderEvent" | tail -1)
    if [ -n "$recent_order" ]; then
        echo "✅ 最近订单事件: $(echo $recent_order | cut -d' ' -f1-3)"
    else
        echo "⚠️  未找到最近的订单事件"
    fi
else
    echo "⚠️  OMS Core 日志文件不存在"
fi
echo ""

# 4. 检查 Match Engine 消费
echo "4. 检查 Match Engine 消费订单..."
echo "----------------------------------------"
if [ -f "match-engine-core/logs/match-engine.log" ]; then
    recent_consume=$(tail -100 match-engine-core/logs/match-engine.log 2>/dev/null | grep -E "OrderEventConsumer|Receive order" | tail -1)
    if [ -n "$recent_consume" ]; then
        echo "✅ 最近消费: $(echo $recent_consume | cut -d' ' -f1-3)"
    else
        echo "⚠️  未找到最近的消费记录"
    fi
else
    echo "⚠️  Match Engine 日志文件不存在"
fi
echo ""

# 5. 检查深度数据发布
echo "5. 检查深度数据发布（Match Engine）..."
echo "----------------------------------------"
if [ -f "match-engine-core/logs/match-engine.log" ]; then
    recent_depth=$(tail -100 match-engine-core/logs/match-engine.log 2>/dev/null | grep -E "Depth published|publishDepth" | tail -1)
    if [ -n "$recent_depth" ]; then
        echo "✅ 最近发布: $(echo $recent_depth | cut -d' ' -f1-3)"
    else
        echo "⚠️  未找到最近的深度发布记录"
    fi
else
    echo "⚠️  Match Engine 日志文件不存在"
fi
echo ""

# 6. 检查 Market Price Core 消费
echo "6. 检查 Market Price Core 消费深度..."
echo "----------------------------------------"
if [ -f "market-price-core/logs/market-price.log" ]; then
    recent_process=$(tail -100 market-price-core/logs/market-price.log 2>/dev/null | grep -E "Process depth|depth event" | tail -1)
    if [ -n "$recent_process" ]; then
        echo "✅ 最近处理: $(echo $recent_process | cut -d' ' -f1-3)"
    else
        echo "⚠️  未找到最近的深度处理记录"
    fi
else
    echo "⚠️  Market Price Core 日志文件不存在"
fi
echo ""

# 7. 检查 Public Push Core 推送
echo "7. 检查 Public Push Core 推送..."
echo "----------------------------------------"
if [ -f "public-push-core/logs/public-push.log" ]; then
    recent_push=$(tail -100 public-push-core/logs/public-push.log 2>/dev/null | grep -E "broadcast.*depth|sendMessage.*depth" | tail -1)
    if [ -n "$recent_push" ]; then
        echo "✅ 最近推送: $(echo $recent_push | cut -d' ' -f1-3)"
    else
        echo "⚠️  未找到最近的推送记录"
    fi
else
    echo "⚠️  Public Push Core 日志文件不存在"
fi
echo ""

# 总结
echo "=========================================="
echo "检查完成"
echo "=========================================="
if [ "$all_running" = true ]; then
    echo "✅ 所有核心服务都在运行"
    echo ""
    echo "下一步："
    echo "1. 创建一个新订单测试"
    echo "2. 查看浏览器控制台的 WebSocket 消息"
    echo "3. 检查各服务的日志文件"
else
    echo "❌ 有服务未运行，请先启动所有服务"
    echo ""
    echo "启动命令："
    echo "  cd match-engine-core && mvn spring-boot:run"
fi
echo ""

