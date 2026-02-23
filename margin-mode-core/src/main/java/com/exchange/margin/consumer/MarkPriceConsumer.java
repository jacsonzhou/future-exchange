package com.exchange.margin.consumer;

import com.exchange.margin.calculator.MarginCalculator;
import com.exchange.margin.dto.LiquidationTriggerEvent;
import com.exchange.margin.dto.MarkPriceEvent;
import com.exchange.margin.entity.PositionMarginDetail;
import com.exchange.margin.mapper.PositionMarginDetailMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 标记价格变动消费者
 *
 * 消费 mark-price-topic，更新仓位保证金详情并检查强平
 */
@Slf4j
@Component
public class MarkPriceConsumer {

    private final PositionMarginDetailMapper positionMarginDetailMapper;
    private final MarginCalculator marginCalculator;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    // 强平触发topic
    private static final String LIQUIDATION_TRIGGER_TOPIC = "liquidation-trigger-topic";

    // 强平阈值：10% = 1000（万分比）
    private static final long LIQUIDATION_THRESHOLD = 1000L;

    public MarkPriceConsumer(PositionMarginDetailMapper positionMarginDetailMapper,
                             MarginCalculator marginCalculator,
                             KafkaTemplate<String, Object> kafkaTemplate) {
        this.positionMarginDetailMapper = positionMarginDetailMapper;
        this.marginCalculator = marginCalculator;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * 消费标记价格变动事件
     *
     * @param event 标记价格事件
     */
    @KafkaListener(topics = "mark-price-topic", groupId = "margin-mode-service")
    @Transactional
    public void consumeMarkPriceEvent(MarkPriceEvent event) {
        try {
            log.info("[MarkPriceConsumer] Received mark price event, symbol={}, markPrice={}, timestamp={}",
                    event.getSymbol(), event.getMarkPrice(), event.getTimestamp());

            // 1. 查询该交易对所有活跃的仓位
            List<PositionMarginDetail> positions = positionMarginDetailMapper.selectBySymbol(event.getSymbol());

            if (positions == null || positions.isEmpty()) {
                log.debug("[MarkPriceConsumer] No active positions for symbol={}", event.getSymbol());
                return;
            }

            // 2. 逐个更新仓位并检查强平
            int updatedCount = 0;
            int liquidationCount = 0;

            for (PositionMarginDetail position : positions) {
                try {
                    // 更新标记价格并检查强平
                    boolean needLiquidation = updatePositionAndCheckLiquidation(position, event);

                    if (needLiquidation) {
                        liquidationCount++;
                    }

                    updatedCount++;
                } catch (Exception e) {
                    log.error("[MarkPriceConsumer] Failed to update position, positionId={}, symbol={}",
                            position.getPositionId(), position.getSymbol(), e);
                }
            }

            log.info("[MarkPriceConsumer] Mark price update completed, symbol={}, " +
                            "totalPositions={}, updated={}, liquidations={}",
                    event.getSymbol(), positions.size(), updatedCount, liquidationCount);

        } catch (Exception e) {
            log.error("[MarkPriceConsumer] Failed to consume mark price event, symbol={}",
                    event.getSymbol(), e);
            // 不抛异常，避免消息重试
        }
    }

    /**
     * 更新仓位标记价格并检查强平
     *
     * @param position 仓位详情
     * @param event 标记价格事件
     * @return 是否触发强平
     */
    private boolean updatePositionAndCheckLiquidation(PositionMarginDetail position, MarkPriceEvent event) {
        Long oldMarkPrice = position.getMarkPrice();
        Long newMarkPrice = event.getMarkPrice();

        // 更新标记价格
        position.setMarkPrice(newMarkPrice);

        // 重新计算未实现盈亏
        Long unrealizedPnl = marginCalculator.calculateUnrealizedPnl(
                position.getPositionSide(),
                position.getEntryPrice(),
                newMarkPrice,
                position.getPositionQty()
        );
        position.setUnrealizedPnl(unrealizedPnl);

        // 重新计算强平价格
        Long liquidationPrice = marginCalculator.calculateLiquidationPrice(
                position.getPositionSide(),
                position.getEntryPrice(),
                position.getLeverage(),
                position.getMaintenanceMarginRate()
        );
        position.setLiquidationPrice(liquidationPrice);

        // 重新计算保证金率
        Long marginRatio = marginCalculator.calculateMarginRatio(
                position.getIsolatedMargin(),
                unrealizedPnl,
                position.getPositionValue()
        );
        position.setMarginRatio(marginRatio);

        // 更新到数据库（使用乐观锁）
        int updated = positionMarginDetailMapper.updateById(position);

        if (updated <= 0) {
            log.warn("[MarkPriceConsumer] Failed to update position (optimistic lock), positionId={}, version={}",
                    position.getPositionId(), position.getVersion());
            return false;
        }

        log.debug("[MarkPriceConsumer] Position updated, positionId={}, markPrice: {} -> {}, " +
                        "unrealizedPnl={}, marginRatio={}, liquidationPrice={}",
                position.getPositionId(), oldMarkPrice, newMarkPrice,
                unrealizedPnl, marginRatio, liquidationPrice);

        // 检查是否需要强平
        boolean needLiquidation = marginRatio != null && marginRatio <= LIQUIDATION_THRESHOLD;

        if (needLiquidation) {
            // 发布强平触发事件
            publishLiquidationTrigger(position, event);
        }

        return needLiquidation;
    }

    /**
     * 发布强平触发事件
     *
     * @param position 仓位详情
     * @param event 标记价格事件
     */
    private void publishLiquidationTrigger(PositionMarginDetail position, MarkPriceEvent event) {
        LiquidationTriggerEvent liquidationEvent = new LiquidationTriggerEvent();

        liquidationEvent.setUserId(position.getUserId());
        liquidationEvent.setPositionId(position.getPositionId());
        liquidationEvent.setSymbol(position.getSymbol());
        liquidationEvent.setMarginMode(position.getMarginMode());
        liquidationEvent.setTriggerType("MARK_PRICE");

        liquidationEvent.setMarginRatio(position.getMarginRatio());
        liquidationEvent.setLiquidationThreshold(LIQUIDATION_THRESHOLD);
        liquidationEvent.setMarkPrice(event.getMarkPrice());
        liquidationEvent.setLiquidationPrice(position.getLiquidationPrice());
        liquidationEvent.setBankruptcyPrice(position.getBankruptcyPrice());

        liquidationEvent.setPositionSide(position.getPositionSide());
        liquidationEvent.setPositionQty(position.getPositionQty());
        liquidationEvent.setEntryPrice(position.getEntryPrice());

        liquidationEvent.setCurrentMargin(position.getIsolatedMargin());
        liquidationEvent.setMaintenanceMargin(
                marginCalculator.calculateMaintenanceMargin(
                        position.getPositionValue(),
                        position.getMaintenanceMarginRate()
                )
        );
        liquidationEvent.setUnrealizedPnl(position.getUnrealizedPnl());
        liquidationEvent.setLeverage(position.getLeverage());

        // 计算优先级：保证金率越低，优先级越高
        int priority = calculateLiquidationPriority(position.getMarginRatio());
        liquidationEvent.setPriority(priority);

        liquidationEvent.setTimestamp(System.currentTimeMillis());
        liquidationEvent.setSequence(event.getSequence());
        liquidationEvent.setRemark("标记价格触发强平");

        // 发送到Kafka
        kafkaTemplate.send(LIQUIDATION_TRIGGER_TOPIC, liquidationEvent);

        log.warn("[MarkPriceConsumer] Liquidation triggered! positionId={}, symbol={}, " +
                        "marginRatio={}%, markPrice={}, liquidationPrice={}, priority={}",
                position.getPositionId(), position.getSymbol(),
                position.getMarginRatio() / 100.0, event.getMarkPrice(),
                position.getLiquidationPrice(), priority);
    }

    /**
     * 计算强平优先级
     *
     * @param marginRatio 保证金率（万分比）
     * @return 优先级：1=最高, 5=最低
     */
    private int calculateLiquidationPriority(Long marginRatio) {
        if (marginRatio == null || marginRatio <= 0) {
            return 1; // 最高优先级
        } else if (marginRatio <= 200) {
            return 1; // <= 2%
        } else if (marginRatio <= 500) {
            return 2; // <= 5%
        } else if (marginRatio <= 800) {
            return 3; // <= 8%
        } else if (marginRatio <= 1000) {
            return 4; // <= 10%
        } else {
            return 5; // > 10%
        }
    }
}
