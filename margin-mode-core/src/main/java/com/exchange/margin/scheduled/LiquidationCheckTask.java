package com.exchange.margin.scheduled;

import com.exchange.margin.calculator.MarginCalculator;
import com.exchange.margin.dto.LiquidationTriggerEvent;
import com.exchange.margin.entity.CrossMarginSnapshot;
import com.exchange.margin.entity.PositionMarginDetail;
import com.exchange.margin.mapper.CrossMarginSnapshotMapper;
import com.exchange.margin.mapper.PositionMarginDetailMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 强平检测定时任务
 *
 * 定期检查需要强平的仓位/账户，并发布强平触发事件
 */
@Slf4j
@Component
public class LiquidationCheckTask {

    private final PositionMarginDetailMapper positionMapper;
    private final CrossMarginSnapshotMapper snapshotMapper;
    private final MarginCalculator marginCalculator;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${margin.liquidation.auto-check:true}")
    private boolean autoCheck;

    @Value("${margin.risk.liquidation-threshold:1000}")
    private long liquidationThreshold;

    // Kafka Topic
    private static final String LIQUIDATION_TRIGGER_TOPIC = "liquidation-trigger-topic";

    // 事件序列号生成器
    private final AtomicLong sequenceGenerator = new AtomicLong(0);

    public LiquidationCheckTask(PositionMarginDetailMapper positionMapper,
                                CrossMarginSnapshotMapper snapshotMapper,
                                MarginCalculator marginCalculator,
                                KafkaTemplate<String, Object> kafkaTemplate) {
        this.positionMapper = positionMapper;
        this.snapshotMapper = snapshotMapper;
        this.marginCalculator = marginCalculator;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * 定时检查强平
     *
     * 默认每500ms执行一次（配置：margin.liquidation.check-interval）
     */
    @Scheduled(fixedDelayString = "${margin.liquidation.check-interval:500}")
    public void checkLiquidation() {
        if (!autoCheck) {
            return;
        }

        try {
            long startTime = System.currentTimeMillis();

            // 1. 检查逐仓仓位
            int isolatedLiquidations = checkIsolatedPositions();

            // 2. 检查全仓账户
            int crossLiquidations = checkCrossAccounts();

            long duration = System.currentTimeMillis() - startTime;

            if (isolatedLiquidations > 0 || crossLiquidations > 0) {
                log.warn("[LiquidationCheckTask] Liquidation check completed, " +
                                "isolatedLiquidations={}, crossLiquidations={}, duration={}ms",
                        isolatedLiquidations, crossLiquidations, duration);
            } else {
                log.debug("[LiquidationCheckTask] Liquidation check completed, " +
                                "no liquidations triggered, duration={}ms", duration);
            }

        } catch (Exception e) {
            log.error("[LiquidationCheckTask] Liquidation check task failed", e);
        }
    }

    /**
     * 检查逐仓仓位
     *
     * @return 触发强平的仓位数量
     */
    private int checkIsolatedPositions() {
        // 查询高风险的逐仓仓位（保证金率 <= 强平阈值）
        List<PositionMarginDetail> riskPositions =
                positionMapper.selectHighRiskIsolatedPositions(liquidationThreshold);

        if (riskPositions == null || riskPositions.isEmpty()) {
            return 0;
        }

        int liquidationCount = 0;

        for (PositionMarginDetail position : riskPositions) {
            try {
                // 再次检查保证金率（防止数据过期）
                if (position.getMarginRatio() != null &&
                        position.getMarginRatio() <= liquidationThreshold) {

                    // 发布强平触发事件
                    publishLiquidationTrigger(position);
                    liquidationCount++;
                }
            } catch (Exception e) {
                log.error("[LiquidationCheckTask] Failed to trigger liquidation for isolated position, " +
                        "positionId={}", position.getPositionId(), e);
            }
        }

        return liquidationCount;
    }

    /**
     * 检查全仓账户
     *
     * @return 触发强平的账户数量
     */
    private int checkCrossAccounts() {
        // 查询需要强平的全仓账户（保证金率 <= 强平阈值）
        List<CrossMarginSnapshot> riskSnapshots = snapshotMapper.selectLiquidationCandidates();

        if (riskSnapshots == null || riskSnapshots.isEmpty()) {
            return 0;
        }

        int liquidationCount = 0;

        for (CrossMarginSnapshot snapshot : riskSnapshots) {
            try {
                // 再次检查保证金率（防止数据过期）
                if (snapshot.getMarginRatio() != null &&
                        snapshot.getMarginRatio() <= liquidationThreshold) {

                    // 查询该用户的全仓仓位
                    List<PositionMarginDetail> crossPositions =
                            positionMapper.selectCrossPositions(snapshot.getUserId());

                    if (crossPositions != null && !crossPositions.isEmpty()) {
                        // 对每个全仓仓位发布强平触发事件
                        for (PositionMarginDetail position : crossPositions) {
                            publishLiquidationTrigger(position);
                            liquidationCount++;
                        }
                    }
                }
            } catch (Exception e) {
                log.error("[LiquidationCheckTask] Failed to trigger liquidation for cross account, " +
                        "userId={}", snapshot.getUserId(), e);
            }
        }

        return liquidationCount;
    }

    /**
     * 发布强平触发事件
     *
     * @param position 仓位详情
     */
    private void publishLiquidationTrigger(PositionMarginDetail position) {
        LiquidationTriggerEvent event = new LiquidationTriggerEvent();

        event.setUserId(position.getUserId());
        event.setPositionId(position.getPositionId());
        event.setSymbol(position.getSymbol());
        event.setMarginMode(position.getMarginMode());
        event.setTriggerType("MARGIN_RATIO");

        event.setMarginRatio(position.getMarginRatio());
        event.setLiquidationThreshold(liquidationThreshold);
        event.setMarkPrice(position.getMarkPrice());
        event.setLiquidationPrice(position.getLiquidationPrice());
        event.setBankruptcyPrice(position.getBankruptcyPrice());

        event.setPositionSide(position.getPositionSide());
        event.setPositionQty(position.getPositionQty());
        event.setEntryPrice(position.getEntryPrice());

        event.setCurrentMargin(position.getIsolatedMargin());

        // 计算维持保证金
        Long maintenanceMargin = marginCalculator.calculateMaintenanceMargin(
                position.getPositionValue(),
                position.getMaintenanceMarginRate()
        );
        event.setMaintenanceMargin(maintenanceMargin);

        event.setUnrealizedPnl(position.getUnrealizedPnl());
        event.setLeverage(position.getLeverage());

        // 计算优先级：保证金率越低，优先级越高
        int priority = calculateLiquidationPriority(position.getMarginRatio());
        event.setPriority(priority);

        event.setTimestamp(System.currentTimeMillis());
        event.setSequence(sequenceGenerator.incrementAndGet());
        event.setRemark("强平检测任务触发");

        // 发送到Kafka
        try {
            kafkaTemplate.send(LIQUIDATION_TRIGGER_TOPIC, event.getUserId().toString(), event);

            log.warn("[LiquidationCheckTask] Liquidation triggered! positionId={}, userId={}, " +
                            "symbol={}, marginMode={}, marginRatio={}%, markPrice={}, " +
                            "liquidationPrice={}, priority={}",
                    position.getPositionId(), position.getUserId(), position.getSymbol(),
                    position.getMarginMode(), position.getMarginRatio() / 100.0,
                    position.getMarkPrice(), position.getLiquidationPrice(), priority);

        } catch (Exception e) {
            log.error("[LiquidationCheckTask] Failed to publish liquidation trigger event, " +
                    "positionId={}", position.getPositionId(), e);
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
            return 1; // 最高优先级：已破产
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

    /**
     * 手动触发强平检查（供外部调用）
     *
     * @param positionId 仓位ID
     * @return 是否触发强平
     */
    public boolean checkPositionLiquidation(Long positionId) {
        try {
            PositionMarginDetail position = positionMapper.selectByPositionId(positionId);
            if (position == null) {
                log.warn("[LiquidationCheckTask] Position not found, positionId={}", positionId);
                return false;
            }

            if (position.getMarginRatio() != null &&
                    position.getMarginRatio() <= liquidationThreshold) {
                publishLiquidationTrigger(position);
                return true;
            }

            return false;
        } catch (Exception e) {
            log.error("[LiquidationCheckTask] Failed to check position liquidation manually, " +
                    "positionId={}", positionId, e);
            return false;
        }
    }
}
