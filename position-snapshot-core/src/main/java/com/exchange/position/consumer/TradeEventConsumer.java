package com.exchange.position.consumer;

import com.exchange.position.dto.TradeEvent;
import com.exchange.position.service.PositionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Trade Event Consumer（成交事件消费者）- 已弃用
 * 
 * ⚠️ 废弃原因：直接消费撮合事件会破坏Ledger作为唯一事实源的原则
 * 请使用 {@link TradeEntryEventConsumer} 替代
 * 
 * 🔥 问题说明：
 * - 原实现直接消费 trade-event（撮合事件），如果Ledger记账失败但持仓已更新，会导致数据不一致
 * - 修复后应消费 trade-entry-{symbol}（账本分录），确保只有Ledger记账成功后持仓才更新
 * 
 * @deprecated 使用 {@link TradeEntryEventConsumer} 消费 trade-entry-{symbol}
 */
@Slf4j
@Component
@Deprecated
public class TradeEventConsumer {
    
    /**
     * 🔥 禁用标志：设置为true以禁用此消费者
     * 修复后应使用 TradeEntryEventConsumer
     */
    private static final boolean DISABLED = true;
    
    @Autowired
    private PositionService positionService;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    /**
     * 消费TradeEvent - 已弃用
     * 
     * ⚠️ 此方法已禁用，请使用 TradeEntryEventConsumer.consumeTradeEntryEvent
     */
    @KafkaListener(
        topics = "${position.kafka.trade-topics}",
        groupId = "position-service-deprecated",
        concurrency = "1",
        autoStartup = "false"  // 🔥 禁用自动启动
    )
    public void consumeTradeEvent(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
        log.info("[TradeEventConsumer] ⬇️ Receive event, topic={}, partition={}, offset={}",
            topic, partition, offset);
        
        try {
            // 1. 解析事件
            TradeEvent event = objectMapper.readValue(message, TradeEvent.class);
            
            log.info("[TradeEventConsumer] Parse event, tradeId={}, symbol={}, price={}, qty={}",
                event.getTradeId(), event.getSymbol(), event.getPrice(), event.getQuantity());
            
            // 2. 调用Service处理
            positionService.onTrade(event);
            
            log.info("[TradeEventConsumer] ✅ Event processed, tradeId={}, offset={}",
                event.getTradeId(), offset);
            
        } catch (Exception e) {
            log.error("[TradeEventConsumer] ❌ Process event error, topic={}, offset={}",
                topic, offset, e);
            throw new RuntimeException("Process trade event failed", e);
        }
    }
}

