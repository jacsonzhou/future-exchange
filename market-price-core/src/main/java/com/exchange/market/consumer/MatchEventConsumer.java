package com.exchange.market.consumer;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.exchange.market.engine.OrderBook;
import com.exchange.market.engine.TradeEngine;
import com.exchange.market.engine.KlineEngine;
import com.exchange.market.model.Trade;
import com.exchange.market.service.MarketDataEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Kafka撮合事件消费者（Match Event Consumer）
 * 
 * 职责：
 * - 消费撮合引擎发布的成交事件
 * - 消费订单簿深度更新事件
 * - 驱动行情数据引擎
 * 
 * 消费策略：
 * - 每个symbol独立分区，保证顺序消费
 * - 批量消费，提高吞吐
 * - 手动ack，确保消息不丢失
 * 
 * 对标：Binance Market Data Feed
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchEventConsumer {

    private final MarketDataEngineService engineService;

    /**
     * 消费成交事件
     * 
     * 修复：
     * - Topic: 使用 trade-event-.* 模式（与 match-engine-core 按 symbol 分 topic 一致）
     * - 支持 byte[] 反序列化（match-engine-core 发布的是 byte[]）
     * 
     * Topic: trade-event-.*（按 symbol 分 topic，如 trade-event-BTCUSDT）
     * Group: market-price-service
     */
    @KafkaListener(
        topicPattern = "trade-event-.*",
        groupId = "${spring.application.name:market-price-service}",
        containerFactory = "matchEventKafkaListenerContainerFactory"
    )
    public void onTradeEvent(List<ConsumerRecord<String, byte[]>> records, Acknowledgment ack) {
        try {
            for (ConsumerRecord<String, byte[]> record : records) {
                // 修复：将 byte[] 转换为 String（match-engine-core 发布的是 JSON 格式的 byte[]）
                String json = new String(record.value(), StandardCharsets.UTF_8);
                processTradeEvent(json);
            }
            // 手动确认
            ack.acknowledge();
        } catch (Exception e) {
            log.error("[Consumer] Failed to process trade events: {}", e.getMessage(), e);
            // 不ack，让Kafka重试
        }
    }

    /**
     * 消费深度更新事件
     * 
     * Topic: market.depth.raw.{symbol}（Match Engine 发布的原始深度数据）
     * 🔥 FIX: 从 market.depth.* 改为 market.depth.raw.*，避免与 public-push-core 竞争
     */
    @KafkaListener(
        topicPattern = "market\\.depth\\.raw\\..*",
        groupId = "${spring.application.name:market-price-service}",
        containerFactory = "depthEventKafkaListenerContainerFactory"
    )
    public void onDepthEvent(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        log.info("[Consumer] >>> onDepthEvent called, records size={}", records.size());
        try {
            for (ConsumerRecord<String, String> record : records) {
                log.info("[Consumer] Processing record: topic={}, partition={}, offset={}, value length={}", 
                    record.topic(), record.partition(), record.offset(), record.value().length());
                processDepthEvent(record.value());
            }
            ack.acknowledge();
            log.info("[Consumer] <<< onDepthEvent completed");
        } catch (Exception e) {
            log.error("[Consumer] Failed to process depth events: {}", e.getMessage(), e);
        }
    }

    /**
     * 处理成交事件
     * 
     * 修复：支持字符串类型的 tradeId（如 "BTCUSDT-1771754637042-3"）
     */
    private void processTradeEvent(String json) {
        try {
            JSONObject event = JSON.parseObject(json);
            String eventType = event.getString("eventType");
            
            if (!"TRADE".equals(eventType)) {
                return;
            }
            
            String symbol = event.getString("symbol");
            long sequence = event.getLongValue("sequence");
            
            // 修复：tradeId 可能是字符串（如 "BTCUSDT-1771754637042-3"），需要特殊处理
            long tradeId;
            Object tradeIdObj = event.get("tradeId");
            if (tradeIdObj instanceof String) {
                // 从字符串中提取数字部分或使用 hashCode
                String tradeIdStr = (String) tradeIdObj;
                try {
                    // 尝试直接解析（如果是纯数字字符串）
                    tradeId = Long.parseLong(tradeIdStr);
                } catch (NumberFormatException e) {
                    // 如果是复杂字符串（如 "BTCUSDT-1771754637042-3"），使用 hashCode
                    tradeId = tradeIdStr.hashCode();
                }
            } else {
                tradeId = event.getLongValue("tradeId");
            }
            
            long price = event.getLongValue("price");
            long quantity = event.getLongValue("quantity");
            boolean isBuyerMaker = event.getBooleanValue("isBuyerMaker");
            long timestamp = event.getLongValue("timestamp");
            
            // 构建Trade对象
            Trade trade = Trade.builder()
                    .tradeId(tradeId)
                    .sequence(sequence)
                    .symbol(symbol)
                    .price(price)
                    .quantity(quantity)
                    .isBuyerMaker(isBuyerMaker)
                    .timestamp(timestamp)
                    .build();
            
            // 异步处理（不阻塞消费线程）
            CompletableFuture.runAsync(() -> {
                engineService.onTrade(symbol, trade);
            });
            
        } catch (Exception e) {
            log.error("[Consumer] Failed to parse trade event: {}", json, e);
        }
    }

    /**
     * 处理深度更新事件
     * 
     * 修复：支持 Match Engine 发布的格式
     * Match Engine 格式: {"e": "depthUpdate", "s": "BTCUSDT", "U": 1, "u": 2, "b": [...], "a": [...]}
     */
    private void processDepthEvent(String json) {
        log.info("[Consumer] =====> processDepthEvent called, json length={}", json.length());
        try {
            JSONObject event = JSON.parseObject(json);
            log.info("[Consumer] JSON parsed, e={}", event.getString("e"));
            
            // Match Engine 格式：e = "depthUpdate", s = symbol
            String eventType = event.getString("e");
            if (!"depthUpdate".equals(eventType)) {
                // 兼容旧格式
                eventType = event.getString("eventType");
                if (!"DEPTH_DELTA".equals(eventType) && !"DEPTH_SNAPSHOT".equals(eventType)) {
                    return;
                }
            }
            
            // 解析 symbol（Match Engine 使用 "s"，旧格式使用 "symbol"）
            String symbol = event.getString("s");
            if (symbol == null) {
                symbol = event.getString("symbol");
            }
            
            // 解析序列号（Match Engine 使用 "U" 和 "u"，旧格式使用 "sequence"）
            long firstUpdateId = event.getLongValue("U");
            long lastUpdateId = event.getLongValue("u");
            if (firstUpdateId == 0 && lastUpdateId == 0) {
                firstUpdateId = event.getLongValue("sequence");
                lastUpdateId = firstUpdateId;
            }
            
            // 解析时间戳（Match Engine 使用 "E"，旧格式使用 "timestamp"）
            long timestamp = event.getLongValue("E");
            if (timestamp == 0) {
                timestamp = event.getLongValue("timestamp");
                if (timestamp == 0) {
                    timestamp = System.currentTimeMillis();
                }
            }
            
            // 判断是否为快照
            boolean isSnapshot = event.getBooleanValue("isSnapshot") || 
                                 event.getBooleanValue("snapshot") ||
                                 (firstUpdateId == 1 && lastUpdateId == 1); // 首次更新视为快照
            
            // 解析买盘和卖盘（Match Engine 使用 "b" 和 "a"，旧格式使用 "bids" 和 "asks"）
            List<long[]> bids = parseDepthLevels(event.getJSONArray("b"));
            if (bids == null || bids.isEmpty()) {
                bids = parseDepthLevels(event.getJSONArray("bids"));
            }
            
            List<long[]> asks = parseDepthLevels(event.getJSONArray("a"));
            if (asks == null || asks.isEmpty()) {
                asks = parseDepthLevels(event.getJSONArray("asks"));
            }
            
            if (bids == null && asks == null) {
                log.warn("[Consumer] No depth data in event: {}", json);
                return;
            }
            
            log.info("[Consumer] Process depth event, symbol={}, firstU={}, lastU={}, bids={}, asks={}", 
                symbol, firstUpdateId, lastUpdateId, 
                bids != null ? bids.size() : 0, asks != null ? asks.size() : 0);
            
            // 转换为 final 变量供 lambda 使用
            final String finalSymbol = symbol;
            final long finalSequence = lastUpdateId;
            final long finalTimestamp = timestamp;
            final List<long[]> finalBids = bids != null ? bids : new ArrayList<>();
            final List<long[]> finalAsks = asks != null ? asks : new ArrayList<>();
            final boolean finalIsSnapshot = isSnapshot;
            
            log.info("[Consumer] Calling engineService, isSnapshot={}, bids={}, asks={}", finalIsSnapshot, finalBids.size(), finalAsks.size());
            if (finalIsSnapshot) {
                // 快照：必须按 Kafka 消费顺序同步处理，避免旧快照覆盖新盘口
                engineService.rebuildOrderBook(finalSymbol, finalBids, finalAsks, finalSequence, finalTimestamp);
            } else {
                // 增量：同步处理，保持同分区消息顺序
                engineService.onDepthDelta(finalSymbol, finalBids, finalAsks, finalSequence, finalTimestamp);
            }
            
        } catch (Exception e) {
            log.error("[Consumer] Failed to parse depth event: {}", json, e);
        }
    }

    /**
     * 解析深度档位
     * 
     * 修复：Match Engine 发布的价格是字符串格式（如 "50200.00000000"），
     * 数量也是字符串格式（如 "1"），
     * 价格需要乘以 PRICE_SCALE 转为 long，数量保持原值
     */
    @SuppressWarnings("unchecked")
    private List<long[]> parseDepthLevels(Object jsonArray) {
        if (jsonArray == null) {
            return null;
        }
        
        final long PRICE_SCALE = 100_000_000L;
        final java.math.BigDecimal SCALE_BD = java.math.BigDecimal.valueOf(PRICE_SCALE);
        
        List<Object> list = (List<Object>) jsonArray;
        List<long[]> result = new java.util.ArrayList<>(list.size());
        
        for (Object item : list) {
            List<Object> pair = (List<Object>) item;
            // 修复：价格是字符串格式，如 "50200.00000000"
            String priceStr = pair.get(0).toString();
            String qtyStr = pair.get(1).toString();
            
            java.math.BigDecimal priceBd = new java.math.BigDecimal(priceStr).setScale(8, RoundingMode.HALF_UP);
            java.math.BigDecimal qtyBd = new java.math.BigDecimal(qtyStr).setScale(8, RoundingMode.HALF_UP);

            // 统一按十进制价格/数量解析，不再做缩放格式猜测。
            long price = priceBd.multiply(SCALE_BD).longValueExact();
            long qty = qtyBd.multiply(SCALE_BD).longValueExact();

            result.add(new long[]{price, qty});
        }
        
        return result;
    }
}
