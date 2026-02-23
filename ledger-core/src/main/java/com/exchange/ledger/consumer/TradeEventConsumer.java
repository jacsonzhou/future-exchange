package com.exchange.ledger.consumer;

import com.exchange.ledger.dto.TradeDTO;
import com.exchange.ledger.service.LedgerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Trade Event Kafka消费者（生产级）
 * 
 * 🔥 核心职责：
 * 1. 消费Kafka: trade-event
 * 2. 调用LedgerService.applyTrade()
 * 3. 写入双录分录
 * 4. 更新AccountSnapshot
 * 
 * 对标：Binance / OKX / Bybit级别
 */
@Slf4j
@Component
public class TradeEventConsumer {
    
    @Autowired
    private LedgerService ledgerService;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    /**
     * 消费成交事件（来自Match Engine）
     * 
     * Topic: trade-event
     * Group: ledger-service
     * 
     * 修复：支持 match-engine-core 发布的 byte[] 格式
     * 
     * 🔥 特性：
     * 1. 并发消费（concurrency=4）
     * 2. 自动提交offset
     * 3. 幂等性保证（LedgerService内部）
     */
    @KafkaListener(
        topics = "trade-event",
        groupId = "ledger-service",
        concurrency = "4"  // 并发消费，提高吞吐
    )
    public void consumeTradeEvent(
            @Payload byte[] messageBytes,  // 修复：支持 byte[] 反序列化
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
        log.info("[TradeEventConsumer] ⬇️ Receive trade event, topic={}, partition={}, offset={}",
            topic, partition, offset);
        
        try {
            // 修复：将 byte[] 转换为 String（match-engine-core 发布的是 JSON 格式的 byte[]）
            String message = new String(messageBytes, StandardCharsets.UTF_8);
            
            // 1. 解析TradeEvent（支持 long 格式的 price/quantity）
            TradeDTO trade = objectMapper.readValue(message, TradeDTO.class);
            
            log.info("[TradeEventConsumer] Parse trade, tradeId={}, maker={}, taker={}, price={}, qty={}",
                trade.getTradeId(), trade.getMakerUserId(), trade.getTakerUserId(),
                trade.getPriceAsBigDecimal(), trade.getQuantityAsBigDecimal());
            
            // 2. 应用到Ledger
            ledgerService.applyTrade(trade);
            
            log.info("[TradeEventConsumer] ✅ Trade applied, tradeId={}, offset={}",
                trade.getTradeId(), offset);
            
        } catch (Exception e) {
            log.error("[TradeEventConsumer] ❌ Process trade event error, topic={}, offset={}",
                topic, offset, e);
            
            // 🔥 生产环境：
            // 1. 记录到死信队列
            // 2. 报警
            // 3. 跳过该消息继续处理（或者停机修复）
            
            // 简化实现：抛异常，让Kafka重试
            throw new RuntimeException("Process trade event failed", e);
        }
    }
}

