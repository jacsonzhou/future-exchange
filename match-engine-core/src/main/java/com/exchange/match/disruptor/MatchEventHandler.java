package com.exchange.match.disruptor;

import com.exchange.match.engine.MatchEngine;
import com.exchange.match.event.MatchEvent;
import com.exchange.match.model.Order;
import com.exchange.match.model.OrderStateEvent;
import com.exchange.match.orderbook.OrderBook;
import com.exchange.match.model.Trade;
import com.exchange.match.publisher.TradePublisher;
import com.exchange.match.wal.MatchWAL;
import com.lmax.disruptor.EventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Disruptor 事件处理器
 * 
 * 核心职责：
 * 1. 调用撮合引擎处理订单
 * 2. 将成交结果写入WAL（Phase 3.1: 异步批量刷盘）
 * 3. 发布成交事件到 trade-event
 * 4. 发布订单状态事件到 order-state-{symbol}（回传OMS）⭐
 * 
 * ============================================
 * Phase 3.1: WAL Async Batch Flush Integration
 * ============================================
 */
@Slf4j
@Component
public class MatchEventHandler implements EventHandler<MatchEvent> {

    @Autowired
    private MatchEngine matchEngine;

    @Autowired(required = false)
    private MatchWAL matchWAL;

    @Autowired(required = false)
    private TradePublisher tradePublisher;

    @Override
    public void onEvent(MatchEvent event, long sequence, boolean endOfBatch) {
        if (event.getOrderCommand() == null) {
            return;
        }

        com.exchange.match.event.OrderCommand command = event.getOrderCommand();

        try {
            // 1. 执行撮合
            List<Trade> trades = matchEngine.onOrder(command);

            // 2. 写入WAL（Phase 3.1: 支持异步批量刷盘）
            if (matchWAL != null) {
                matchWAL.append(command, trades, sequence);
            }

            // 3. 发布成交事件到 Kafka（通知 Ledger/Market/Price 等服务）
            if (tradePublisher != null) {
                for (Trade trade : trades) {
                    publishTrade(trade);
                }
            } else {
                for (Trade trade : trades) {
                    log.info("[MatchEventHandler] Trade generated (publisher unavailable): tradeId={}",
                        trade.getTradeId());
                }
            }

            // 4. 发布订单状态事件到 order-state-{symbol}（回传OMS）⭐
            if (tradePublisher != null) {
                publishOrderState(command, trades, sequence);
            }

            // 5. 清空事件（避免内存泄漏）
            event.clear();

        } catch (Exception e) {
            log.error("[MatchEventHandler] Failed to process order command: orderId={}, seq={}",
                command.getOrderId(), sequence, e);
        }
    }

    /**
     * 发布成交事件到 Kafka
     */
    private void publishTrade(Trade trade) {
        try {
            tradePublisher.publishTrade(trade);
        } catch (Exception e) {
            log.error("[MatchEventHandler] Failed to publish trade: tradeId={}", trade.getTradeId(), e);
        }
    }

    /**
     * 发布订单状态事件（回传OMS）
     *
     * Topic: order-state-{symbol}
     * 状态机：
     * - NEW: 限价单未成交，已加入订单簿
     * - PARTIALLY_FILLED: 部分成交，仍在订单簿中
     * - FILLED: 完全成交，已从订单簿移除
     * - CANCELED: 撤单成功 / 市价单IOC未成交部分
     */
    private void publishOrderState(com.exchange.match.event.OrderCommand command, List<Trade> trades, long sequence) {
        try {
            String symbol = command.getSymbol();
            Long orderId = command.getOrderId();
            String eventType = command.getEventType();
            boolean isMarket = "MARKET".equals(command.getOrderType());
            boolean isCancel = "ORDER_CANCEL".equals(eventType) || "ORDER_FORCE_CANCEL".equals(eventType);

            // 计算本单本次成交总量和成交均价
            BigDecimal filledDelta = BigDecimal.ZERO;
            BigDecimal totalAmount = BigDecimal.ZERO;
            for (Trade trade : trades) {
                if (orderId.equals(trade.getMakerOrderId()) || orderId.equals(trade.getTakerOrderId())) {
                    filledDelta = filledDelta.add(trade.getQuantity());
                    totalAmount = totalAmount.add(trade.getPrice().multiply(trade.getQuantity()));
                }
            }
            BigDecimal avgPrice = BigDecimal.ZERO;
            if (filledDelta.compareTo(BigDecimal.ZERO) > 0) {
                avgPrice = totalAmount.divide(filledDelta, 8, java.math.RoundingMode.HALF_UP);
            }

            String status;
            OrderBook orderBook = matchEngine.getOrderBook(symbol);
            Order orderInBook = (orderBook != null) ? orderBook.getOrder(orderId) : null;

            if (isCancel) {
                // 撤单命令：根据订单是否在订单簿中判断结果
                if (orderInBook != null) {
                    // 订单仍在订单簿中，成功移除 → CANCELED
                    status = "CANCELED";
                } else {
                    // 订单已不在订单簿中：可能已完全成交，或市价单 IOC 已被丢弃
                    BigDecimal originalQty = (command.getQuantity() != null)
                        ? new BigDecimal(command.getQuantity()) : BigDecimal.ZERO;
                    if (filledDelta.compareTo(BigDecimal.ZERO) > 0) {
                        // 本批有成交记录（作为 taker 或 maker）→ 视为已成交
                        status = filledDelta.compareTo(originalQty) >= 0 ? "FILLED" : "PARTIALLY_FILLED";
                    } else {
                        // 无成交记录：保守回传 CANCELED（OMS 会根据 filledQuantity 做终态保护）
                        status = "CANCELED";
                    }
                }
            } else if (orderInBook != null) {
                // 订单仍在订单簿中
                status = filledDelta.compareTo(BigDecimal.ZERO) > 0 ? "PARTIALLY_FILLED" : "NEW";
            } else {
                // 订单不在订单簿中
                BigDecimal originalQty = (command.getQuantity() != null)
                    ? new BigDecimal(command.getQuantity()) : BigDecimal.ZERO;
                if (filledDelta.compareTo(BigDecimal.ZERO) == 0) {
                    // 无成交：市价单 IOC 未成交部分直接取消
                    status = "CANCELED";
                } else if (filledDelta.compareTo(originalQty) >= 0) {
                    status = "FILLED";
                } else {
                    // 部分成交但不在订单簿：市价单 IOC 部分成交后剩余取消
                    status = isMarket ? "CANCELED" : "FILLED";
                }
            }

            OrderStateEvent stateEvent = new OrderStateEvent();
            stateEvent.setEventId("os-" + orderId + "-" + System.currentTimeMillis());
            stateEvent.setSymbol(symbol);
            stateEvent.setOrderId(orderId);
            stateEvent.setFilledQuantityDelta(filledDelta);
            stateEvent.setAvgPrice(avgPrice);
            stateEvent.setStatus(status);
            stateEvent.setMatchSequence(command.getSequence() != null ? command.getSequence() : sequence);
            stateEvent.setEventTime(System.currentTimeMillis());

            tradePublisher.publishOrderState(stateEvent);

            log.info("[MatchEventHandler] Order state published: orderId={}, status={}, filledDelta={}",
                orderId, status, filledDelta);

        } catch (Exception e) {
            log.error("[MatchEventHandler] Failed to publish order state: orderId={}", command.getOrderId(), e);
        }
    }

    /**
     * 获取撮合引擎（用于查询）
     */
    public MatchEngine getMatchEngine() {
        return matchEngine;
    }
}




