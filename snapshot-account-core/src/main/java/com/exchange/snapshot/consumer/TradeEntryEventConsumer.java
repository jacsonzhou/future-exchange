package com.exchange.snapshot.consumer;

import com.exchange.snapshot.dto.TradeEntryEvent;
import com.exchange.snapshot.service.AccountSnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Trade Entry Event Consumer（Kafka消费者）
 * 
 * 🔥 核心职责：
 * 1. 消费Ledger-Core发布的TradeEntryEvent
 * 2. 调用AccountSnapshotService更新Snapshot
 * 3. 保证顺序性和幂等性
 * 
 * Topic: trade-entry-{symbol}
 * Partition: 1（单Symbol单线程）
 * Group: snapshot-service
 * 
 * 对标：Binance / OKX / Bybit级别
 */
@Slf4j
@Component
public class TradeEntryEventConsumer {
    
    @Autowired
    private AccountSnapshotService accountSnapshotService;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    /**
     * 消费TradeEntryEvent
     * 
     * 🔥 特性：
     * 1. 单线程消费（concurrency=1）保证顺序
     * 2. 按Symbol分topic，水平扩展
     * 3. 自动提交offset
     */
    @KafkaListener(
        topics = "${snapshot.kafka.topics}", // trade-entry-BTCUSDT,trade-entry-ETHUSDT,account-entry-SYSTEM
        groupId = "snapshot-service",
        concurrency = "1" // 单线程保证顺序
    )
    public void consumeTradeEntry(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
        log.info("[TradeEntryConsumer] ⬇️ Receive event, topic={}, partition={}, offset={}",
            topic, partition, offset);
        
        try {
            // 1. 解析事件
            TradeEntryEvent event = objectMapper.readValue(message, TradeEntryEvent.class);
            
            log.info("[TradeEntryConsumer] Parse event, tradeId={}, symbol={}, bizSeq={}, entries={}",
                event.getTradeId(), event.getSymbol(), event.getBizSeq(), event.getEntries().size());
            
            // 2. 调用Service处理
            accountSnapshotService.onTradeEntryEvent(event);
            
            log.info("[TradeEntryConsumer] ✅ Event processed, tradeId={}, offset={}",
                event.getTradeId(), offset);
            
        } catch (Exception e) {
            log.error("[TradeEntryConsumer] ❌ Process event error, topic={}, offset={}",
                topic, offset, e);
            
            // 🔥 生产环境：
            // 1. 记录到死信队列
            // 2. 报警
            // 3. 跳过该消息继续处理（或者停机修复）
            
            // 简化实现：抛异常，让Kafka重试
            throw new RuntimeException("Process trade entry event failed", e);
        }
    }
}
