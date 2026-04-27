package com.exchange.margin.consumer;

import com.exchange.margin.calculator.MarginCalculator;
import com.exchange.margin.dto.LiquidationTriggerEvent;
import com.exchange.margin.dto.MarkPriceEvent;
import com.exchange.margin.entity.PositionMarginDetail;
import com.exchange.margin.mapper.PositionMarginDetailMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 标记价格变动消费者
 *
 * 消费 mark-price-update（兼容旧 mark-price-topic）并触发强平检查
 */
@Slf4j
@Component
public class MarkPriceConsumer {

    private final PositionMarginDetailMapper positionMarginDetailMapper;
    private final MarginCalculator marginCalculator;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    // 强平触发topic
    private static final String LIQUIDATION_TRIGGER_TOPIC = "liquidation-trigger-topic";

    // 强平阈值：10% = 1000（万分比）
    private static final long LIQUIDATION_THRESHOLD = 1000L;

    @Value("${margin.liquidation.dedup.enabled:true}")
    private boolean liquidationDedupEnabled;

    @Value("${margin.liquidation.dedup.window-ms:120000}")
    private long liquidationDedupWindowMs;

    @Value("${margin.liquidation.dedup.max-cache-size:200000}")
    private int liquidationDedupMaxCacheSize;

    private final ConcurrentHashMap<String, Long> liquidationDedupCache = new ConcurrentHashMap<>();
    private final AtomicLong dedupCleanupCounter = new AtomicLong(0);

    public MarkPriceConsumer(PositionMarginDetailMapper positionMarginDetailMapper,
                             MarginCalculator marginCalculator,
                             KafkaTemplate<String, Object> kafkaTemplate,
                             ObjectMapper objectMapper) {
        this.positionMarginDetailMapper = positionMarginDetailMapper;
        this.marginCalculator = marginCalculator;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 消费标记价格变动事件
     *
     * @param event 标记价格事件
     */
    @KafkaListener(
            topics = "${margin.kafka.mark-price-topic:mark-price-update}",
            groupId = "${spring.kafka.consumer.group-id:margin-mode-service}"
    )
    @Transactional
    public void consumeMarkPriceEvent(String payload) {
        MarkPriceEvent event = parseMarkPriceEvent(payload);
        if (event == null || event.getSymbol() == null || event.getMarkPrice() == null) {
            return;
        }

        try {
            log.info("[MarkPriceConsumer] Received mark price event, symbol={}, markPrice={}, markPriceId={}, timestamp={}",
                    event.getSymbol(), event.getMarkPrice(), event.getMarkPriceId(), event.getTimestamp());

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

    private MarkPriceEvent parseMarkPriceEvent(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode data = root.path("data");
            boolean updateEnvelope = !data.isMissingNode() && data.isObject();

            MarkPriceEvent event = new MarkPriceEvent();
            JsonNode sourceNode = updateEnvelope ? data : root;

            event.setSymbol(readText(sourceNode, "symbol"));
            event.setMarkPrice(readScaledLong(sourceNode.get("markPrice")));
            event.setIndexPrice(readScaledLong(sourceNode.get("indexPrice")));
            event.setLastPrice(readScaledLong(sourceNode.get("lastPrice")));
            event.setFundingRate(readScaledLong(sourceNode.get("fundingRate")));
            event.setNextFundingTime(readScaledLong(sourceNode.get("nextFundingTime")));
            event.setTimestamp(readLong(sourceNode, "timestamp", readLong(root, "eventTime", System.currentTimeMillis())));
            event.setSequence(readLong(sourceNode, "sourceOffset", readLong(sourceNode, "sequence", readLong(root, "eventTime", 0L))));
            event.setMarkPriceId(readText(sourceNode, "markPriceId"));
            event.setIndexPriceId(readText(sourceNode, "indexPriceId"));
            event.setSourceTopic(readText(sourceNode, "sourceTopic"));
            event.setSourceOffset(readLong(sourceNode, "sourceOffset", null));

            if (event.getMarkPrice() == null || event.getMarkPrice() <= 0) {
                log.warn("[MarkPriceConsumer] Ignore mark price event with invalid markPrice, payload={}", payload);
                return null;
            }
            return event;
        } catch (Exception e) {
            log.error("[MarkPriceConsumer] Failed to parse mark price payload, payload={}", payload, e);
            return null;
        }
    }

    private String readText(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.trim();
    }

    private Long readLong(JsonNode node, String field, Long defaultValue) {
        if (node == null || node.isMissingNode()) {
            return defaultValue;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        try {
            return Long.parseLong(value.asText().trim());
        } catch (Exception ignore) {
            return defaultValue;
        }
    }

    private Long readScaledLong(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        try {
            String text = value.asText();
            if (text == null || text.isBlank()) {
                return null;
            }
            BigDecimal decimal = new BigDecimal(text.trim());
            return decimal.movePointRight(8)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();
        } catch (Exception ignore) {
            return null;
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
                position.getPositionQty(),
                position.getEntryPrice(),
                newMarkPrice
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

        // 更新到数据库（使用 mapper 显式乐观锁，避免 MP updateById 参数注入差异）
        long now = System.currentTimeMillis();
        int updated = positionMarginDetailMapper.updatePositionMargin(
                position.getPositionId(),
                position.getPositionValue(),
                position.getPositionMargin(),
                position.getUnrealizedPnl(),
                position.getLiquidationPrice(),
                position.getMarginRatio(),
                position.getMarkPrice(),
                now,
                position.getVersion()
        );

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
        Long sequence = resolveSequence(event);
        liquidationEvent.setSequence(sequence);
        String dedupKey = buildDedupKey(position.getPositionId(), liquidationEvent.getTriggerType(), sequence);
        if (!registerDedupKey(dedupKey)) {
            log.info("[MarkPriceConsumer] Skip duplicate liquidation trigger, dedupKey={}, positionId={}, symbol={}",
                    dedupKey, position.getPositionId(), position.getSymbol());
            return;
        }
        liquidationEvent.setRemark("标记价格触发强平|dedupKey=" + dedupKey);

        // 发送到Kafka（显式序列化为JSON，兼容当前StringSerializer配置）
        try {
            String payload = objectMapper.writeValueAsString(liquidationEvent);
            kafkaTemplate.send(LIQUIDATION_TRIGGER_TOPIC, String.valueOf(liquidationEvent.getUserId()), payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize liquidation trigger event", e);
        }

        log.warn("[MarkPriceConsumer] Liquidation triggered! positionId={}, symbol={}, " +
                        "marginRatio={}%, markPrice={}, liquidationPrice={}, priority={}",
                position.getPositionId(), position.getSymbol(),
                position.getMarginRatio() / 100.0, event.getMarkPrice(),
                position.getLiquidationPrice(), priority);
    }

    private Long resolveSequence(MarkPriceEvent event) {
        if (event != null && event.getSequence() != null && event.getSequence() > 0) {
            return event.getSequence();
        }
        if (event != null && event.getTimestamp() != null && event.getTimestamp() > 0) {
            return event.getTimestamp();
        }
        return System.currentTimeMillis();
    }

    private String buildDedupKey(Long positionId, String triggerType, Long sequence) {
        long safePositionId = positionId == null ? 0L : positionId;
        String safeTriggerType = (triggerType == null || triggerType.isBlank()) ? "UNKNOWN" : triggerType.trim().toUpperCase();
        long safeSequence = sequence == null ? 0L : sequence;
        return safePositionId + ":" + safeTriggerType + ":" + safeSequence;
    }

    private boolean registerDedupKey(String dedupKey) {
        if (!liquidationDedupEnabled || dedupKey == null || dedupKey.isBlank()) {
            return true;
        }

        long now = System.currentTimeMillis();
        cleanupDedupCache(now);

        Long existing = liquidationDedupCache.putIfAbsent(dedupKey, now);
        if (existing == null) {
            return true;
        }

        long windowMs = Math.max(1_000L, liquidationDedupWindowMs);
        if (now - existing > windowMs) {
            return liquidationDedupCache.replace(dedupKey, existing, now);
        }
        return false;
    }

    private void cleanupDedupCache(long now) {
        long tick = dedupCleanupCounter.incrementAndGet();
        if (liquidationDedupCache.isEmpty()) {
            return;
        }
        if (liquidationDedupCache.size() <= liquidationDedupMaxCacheSize && tick % 256 != 0) {
            return;
        }

        long expireBefore = now - Math.max(1_000L, liquidationDedupWindowMs);
        for (var entry : liquidationDedupCache.entrySet()) {
            Long seenAt = entry.getValue();
            if (seenAt != null && seenAt < expireBefore) {
                liquidationDedupCache.remove(entry.getKey(), seenAt);
            }
        }
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
