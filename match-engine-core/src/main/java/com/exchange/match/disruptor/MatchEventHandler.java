package com.exchange.match.disruptor;

import com.exchange.match.engine.MatchEngine;
import com.exchange.match.event.OrderCommandEvent;
import com.exchange.match.model.Trade;
import com.exchange.match.wal.MatchWAL;
import com.lmax.disruptor.EventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Disruptor 事件处理器
 * 
 * 核心职责：
 * 1. 调用撮合引擎处理订单
 * 2. 将成交结果写入WAL（Phase 3.1: 异步批量刷盘）
 * 3. 发布成交事件
 * 
 * ============================================
 * Phase 3.1: WAL Async Batch Flush Integration
 * ============================================
 */
@Slf4j
@Component
public class MatchEventHandler implements EventHandler<OrderCommandEvent> {
    
    /**
     * 撮合引擎（单例，内存态）
     */
    private final MatchEngine matchEngine = new MatchEngine();
    
    @Autowired(required = false)
    private MatchWAL matchWAL;
    
    @Override
    public void onEvent(OrderCommandEvent event, long sequence, boolean endOfBatch) {
        if (event.getCommand() == null) {
            return;
        }
        
        try {
            // 1. 执行撮合
            List<Trade> trades = matchEngine.onOrder(event.getCommand());
            
            // 2. 写入WAL（Phase 3.1: 支持异步批量刷盘）
            if (matchWAL != null) {
                matchWAL.append(event.getCommand(), trades, sequence);
            }
            
            // 3. 发布成交事件（通知其他服务）
            for (Trade trade : trades) {
                publishTrade(trade);
            }
            
            // 4. 清空事件（避免内存泄漏）
            event.clear();
            
        } catch (Exception e) {
            log.error("[MatchEventHandler] Failed to process order command: orderId={}, seq={}", 
                event.getCommand().getOrderId(), sequence, e);
        }
    }
    
    /**
     * 发布成交事件
     * TODO: 集成Kafka或其他消息队列
     */
    private void publishTrade(Trade trade) {
        log.info("[MatchEventHandler] Trade published: tradeId={}, symbol={}, price={}, qty={}, maker={}, taker={}", 
            trade.getTradeId(), 
            trade.getSymbol(), 
            trade.getPrice(), 
            trade.getQuantity(),
            trade.getMakerOrderId(),
            trade.getTakerOrderId());
        
        // TODO: 发送到Kafka topic: trade-events
        // kafkaTemplate.send("trade-events", trade);
    }
    
    /**
     * 获取撮合引擎（用于查询）
     */
    public MatchEngine getMatchEngine() {
        return matchEngine;
    }
}

