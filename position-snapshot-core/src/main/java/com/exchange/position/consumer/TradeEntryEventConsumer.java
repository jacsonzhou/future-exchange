package com.exchange.position.consumer;

import com.exchange.position.dto.TradeEntryEvent;
import com.exchange.position.service.PositionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Trade Entry Event Consumer（账本分录消费者）
 * 
 * 🔥 核心职责：
 * 1. 消费Ledger-Core发布的TradeEntryEvent
 * 2. 从账本分录中提取持仓变动信息
 * 3. 调用PositionService更新持仓快照
 * 
 * 🔥 关键修复：
 * - 原实现直接消费 trade-event（撮合事件），破坏了Ledger作为唯一事实源的原则
 * - 新实现消费 trade-entry-{symbol}（账本分录），确保只有Ledger记账成功后持仓才更新
 * - 这样即使撮合成功但Ledger记账失败，持仓也不会错误更新
 * 
 * Topic: trade-entry-{symbol}
 * Partition: 1（单Symbol单线程，保证顺序）
 * Group: position-service
 * 
 * 对标：Binance / OKX / Bybit级别
 */
@Slf4j
@Component
public class TradeEntryEventConsumer {
    
    @Autowired
    private PositionService positionService;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    /**
     * 支持的Symbol列表，用于构建Topic
     */
    @Value("${position.kafka.trade-entry-symbols:BTCUSDT,ETHUSDT}")
    private String tradeEntrySymbols;
    
    private volatile List<String> symbolList;
    
    /**
     * 获取Symbol列表（延迟初始化）
     */
    private List<String> getSymbolList() {
        if (symbolList == null) {
            synchronized (this) {
                if (symbolList == null) {
                    List<String> configuredSymbols = Arrays.stream(tradeEntrySymbols.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
                    symbolList = configuredSymbols.isEmpty() ? Collections.emptyList() : configuredSymbols;
                    if (symbolList.isEmpty()) {
                        log.warn("[TradeEntryConsumer] trade-entry-symbols is empty, consume all trade-entry-* symbols");
                    } else {
                        log.info("[TradeEntryConsumer] Initialized with symbols: {}", symbolList);
                    }
                }
            }
        }
        return symbolList;
    }
    
    /**
     * 消费TradeEntryEvent（来自Ledger）
     * 
     * 🔥 特性：
     * 1. 单线程消费（concurrency=1）保证顺序
     * 2. 按Symbol分topic，水平扩展
     * 3. 自动提交offset
     * 
     * 消费Topics示例：trade-entry-BTCUSDT,trade-entry-ETHUSDT
     */
    @KafkaListener(
        topicPattern = "trade-entry-.*",
        groupId = "position-service",
        concurrency = "1" // 单线程保证顺序
    )
    public void consumeTradeEntryEvent(
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
                event.getTradeId(), event.getSymbol(), event.getBizSeq(), 
                event.getEntries() != null ? event.getEntries().size() : 0);

            // SYSTEM事件（如冻结/解冻）不影响持仓，直接跳过并提交offset，避免阻塞消费
            if ("SYSTEM".equalsIgnoreCase(event.getSymbol()) || "trade-entry-SYSTEM".equalsIgnoreCase(topic)) {
                log.debug("[TradeEntryConsumer] Skip system event, topic={}, tradeId={}", topic, event.getTradeId());
                return;
            }

            // 仅处理配置内交易对，避免误消费无关topic导致阻塞
            if (!isSymbolAllowed(event.getSymbol())) {
                log.debug("[TradeEntryConsumer] Skip unsupported symbol event, topic={}, symbol={}, tradeId={}, supported={}",
                    topic, event.getSymbol(), event.getTradeId(), getSymbolList());
                return;
            }
            
            // 2. 调用Service处理（从账本分录中提取持仓变动）
            positionService.onTradeEntryEvent(event);
            
            log.info("[TradeEntryConsumer] ✅ Event processed, tradeId={}, offset={}",
                event.getTradeId(), offset);
            
        } catch (Exception e) {
            log.error("[TradeEntryConsumer] ❌ Process event error, topic={}, offset={}",
                topic, offset, e);

            // 避免SYSTEM topic 的脏数据卡死消费线程
            if ("trade-entry-SYSTEM".equalsIgnoreCase(topic)) {
                log.warn("[TradeEntryConsumer] Skip poisoned SYSTEM message, topic={}, offset={}", topic, offset);
                return;
            }

            // 非SYSTEM消息继续重试，避免真实成交消息丢失
            throw new RuntimeException("Process trade entry event failed", e);
        }
    }

    private boolean isSymbolAllowed(String symbol) {
        List<String> configured = getSymbolList();
        if (configured.isEmpty()) {
            return true;
        }
        return configured.contains(symbol);
    }
}
