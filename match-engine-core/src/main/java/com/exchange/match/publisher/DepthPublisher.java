package com.exchange.match.publisher;

import com.exchange.match.orderbook.OrderBook;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 深度数据发布器
 * 
 * 职责：
 * 1. 从 OrderBook 获取深度数据
 * 2. 序列化为标准格式
 * 3. 发送到 Kafka (market.depth.{symbol})
 * 
 * 数据格式（兼容 Binance API）：
 * {
 *   "e": "depthUpdate",     // 事件类型
 *   "E": 123456789,         // 事件时间
 *   "s": "BTCUSDT",         // 交易对
 *   "U": 100,               // 第一个更新ID
 *   "u": 200,               // 最后一个更新ID
 *   "b": [["price", "qty"], ...],  // 买单深度
 *   "a": [["price", "qty"], ...]   // 卖单深度
 * }
 */
@Slf4j
@Component
public class DepthPublisher {
    
    @Autowired
    private KafkaTemplate<String, byte[]> kafkaTemplate;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    /**
     * 深度 Topic 模板
     */
    @Value("${match.kafka.topic.depth:market.depth.{symbol}}")
    private String depthTopicTemplate;
    
    /**
     * 价格精度（小数位）
     */
    @Value("${match.depth.price-scale:2}")
    private int priceScale;
    
    /**
     * 数量精度（小数位）
     */
    @Value("${match.depth.quantity-scale:4}")
    private int quantityScale;
    
    /**
     * 发布深度数据
     * 
     * @param symbol 交易对
     * @param orderBook 订单簿
     * @param lastSeq 最后序列号（用于生成更新ID）
     */
    public void publishDepth(String symbol, OrderBook orderBook, long lastSeq) {
        log.info("[DepthPublisher] 🔥 Start publish depth, symbol={}, lastSeq={}", symbol, lastSeq);
        try {
            // 获取深度数据（20档）
            Map<String, List<List<String>>> depthData = orderBook.getDepthData(20);
            
            if (depthData == null || depthData.isEmpty()) {
                log.warn("[DepthPublisher] ⚠️ Empty depth data, symbol={}", symbol);
                return;
            }
            
            List<List<String>> bids = depthData.get("bids");
            List<List<String>> asks = depthData.get("asks");
            
            log.info("[DepthPublisher] Depth data retrieved, symbol={}, bids={}, asks={}", 
                symbol, bids != null ? bids.size() : 0, asks != null ? asks.size() : 0);
            
            // 构建 Kafka 消息
            Map<String, Object> message = new HashMap<>();
            message.put("e", "depthUpdate");           // 事件类型
            message.put("E", System.currentTimeMillis()); // 事件时间
            message.put("s", symbol);                   // 交易对
            message.put("U", lastSeq);                  // 第一个更新ID
            message.put("u", lastSeq + 1);              // 最后一个更新ID
            message.put("b", bids != null ? bids : new ArrayList<>());    // 买盘
            message.put("a", asks != null ? asks : new ArrayList<>());    // 卖盘
            
            // 序列化
            String json = objectMapper.writeValueAsString(message);
            byte[] value = json.getBytes(StandardCharsets.UTF_8);
            
            log.info("[DepthPublisher] Message serialized, symbol={}, size={} bytes", symbol, value.length);
            
            // 发送
            String topic = depthTopicTemplate.replace("{symbol}", symbol);
            String key = symbol;
            
            log.info("[DepthPublisher] Sending to Kafka, topic={}, key={}", topic, key);
            
            CompletableFuture<SendResult<String, byte[]>> future = kafkaTemplate.send(topic, key, value);
            
            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("[DepthPublisher] ✅ Depth published successfully, symbol={}, bids={}, asks={}, partition={}, offset={}",
                        symbol,
                        bids != null ? bids.size() : 0,
                        asks != null ? asks.size() : 0,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
                } else {
                    log.error("[DepthPublisher] ❌ Depth publish failed, symbol={}", symbol, ex);
                }
            });
            
        } catch (Exception e) {
            log.error("[DepthPublisher] ❌ Publish depth error, symbol={}", symbol, e);
        }
    }
}
