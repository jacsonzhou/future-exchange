package com.exchange.margin.producer;

import com.exchange.margin.calculator.MarginCalculator;
import com.exchange.margin.dto.RiskWarningEvent;
import com.exchange.margin.entity.CrossMarginSnapshot;
import com.exchange.margin.entity.PositionMarginDetail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 风险预警事件生产者
 *
 * 发布到 risk-warning-topic，供 Risk Monitor Service 和 Notification Service 消费
 */
@Slf4j
@Component
public class RiskWarningProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MarginCalculator marginCalculator;

    // Topic名称
    private static final String RISK_WARNING_TOPIC = "risk-warning-topic";

    // 风险阈值（万分比）
    private static final long SAFE_THRESHOLD = 10000L;      // 100%
    private static final long WARNING_THRESHOLD = 5000L;     // 50%
    private static final long DANGER_THRESHOLD = 2000L;      // 20%
    private static final long CRITICAL_THRESHOLD = 1000L;    // 10%

    public RiskWarningProducer(KafkaTemplate<String, Object> kafkaTemplate,
                               MarginCalculator marginCalculator) {
        this.kafkaTemplate = kafkaTemplate;
        this.marginCalculator = marginCalculator;
    }

    /**
     * 发布逐仓风险预警
     *
     * @param position 仓位详情
     * @param markPrice 当前标记价格
     */
    public void publishIsolatedRiskWarning(PositionMarginDetail position, Long markPrice) {
        Long marginRatio = position.getMarginRatio();

        if (marginRatio == null || marginRatio >= SAFE_THRESHOLD) {
            return; // 安全区域，无需预警
        }

        String riskLevel = determineRiskLevel(marginRatio);
        boolean sendNotification = shouldSendNotification(marginRatio);

        RiskWarningEvent event = new RiskWarningEvent();
        event.setUserId(position.getUserId());
        event.setPositionId(position.getPositionId());
        event.setSymbol(position.getSymbol());
        event.setMarginMode("ISOLATED");
        event.setRiskLevel(riskLevel);

        event.setMarginRatio(marginRatio);
        event.setCurrentMargin(position.getIsolatedMargin());

        // 计算维持保证金
        Long maintenanceMargin = marginCalculator.calculateMaintenanceMargin(
                position.getPositionValue(),
                position.getMaintenanceMarginRate()
        );
        event.setMaintenanceMargin(maintenanceMargin);

        event.setUnrealizedPnl(position.getUnrealizedPnl());
        event.setMarkPrice(markPrice != null ? markPrice : position.getMarkPrice());
        event.setLiquidationPrice(position.getLiquidationPrice());

        // 计算距离强平的价格差
        Long liquidationGap = position.getLiquidationGap();
        event.setLiquidationGap(liquidationGap);

        // 计算距离强平的百分比
        if (markPrice != null && position.getLiquidationPrice() != null && markPrice > 0) {
            Long gapPercent = Math.abs(position.getLiquidationPrice() - markPrice) * 10000 / markPrice;
            event.setLiquidationGapPercent(gapPercent);
        }

        event.setLeverage(position.getLeverage());

        // 判断是否需要追加保证金
        Long gap = maintenanceMargin - (position.getIsolatedMargin() +
                (position.getUnrealizedPnl() != null ? position.getUnrealizedPnl() : 0L));
        boolean needAddMargin = gap > 0;
        event.setNeedAddMargin(needAddMargin);
        event.setSuggestedAddAmount(needAddMargin ? gap * 2 : 0L);

        event.setTimestamp(System.currentTimeMillis());
        event.setSendNotification(sendNotification);

        // 构建预警消息
        String message = String.format("逐仓仓位风险预警 [%s]：交易对=%s, 保证金率=%.2f%%, " +
                        "当前保证金=%d, 强平价格=%d, 标记价格=%d, 杠杆=%dx",
                riskLevel, position.getSymbol(), marginRatio / 100.0,
                position.getIsolatedMargin(), position.getLiquidationPrice(),
                event.getMarkPrice(), position.getLeverage());

        if (needAddMargin) {
            message += String.format(", 建议追加保证金=%d", event.getSuggestedAddAmount());
        }

        event.setMessage(message);

        publishEvent(event);
    }

    /**
     * 发布全仓风险预警
     *
     * @param snapshot 全仓快照
     */
    public void publishCrossRiskWarning(CrossMarginSnapshot snapshot) {
        Long marginRatio = snapshot.getMarginRatio();

        if (marginRatio == null || marginRatio >= SAFE_THRESHOLD) {
            return; // 安全区域，无需预警
        }

        String riskLevel = determineRiskLevel(marginRatio);
        boolean sendNotification = shouldSendNotification(marginRatio);

        RiskWarningEvent event = new RiskWarningEvent();
        event.setUserId(snapshot.getUserId());
        event.setPositionId(null);
        event.setSymbol(null);
        event.setMarginMode("CROSS");
        event.setRiskLevel(riskLevel);

        event.setMarginRatio(marginRatio);
        event.setCurrentMargin(snapshot.getCrossMargin());

        // 计算全仓总维持保证金
        Long totalMaintenanceMargin = marginCalculator.calculateMaintenanceMargin(
                snapshot.getTotalPositionValue(),
                50L // 默认维持保证金率0.5%
        );
        event.setMaintenanceMargin(totalMaintenanceMargin);

        event.setUnrealizedPnl(snapshot.getTotalUnrealizedPnl());
        event.setMarkPrice(null);
        event.setLiquidationPrice(null);
        event.setLiquidationGap(null);
        event.setLiquidationGapPercent(null);
        event.setLeverage(null);

        // 判断是否需要追加保证金
        Long gap = totalMaintenanceMargin - (snapshot.getCrossMargin() + snapshot.getTotalUnrealizedPnl());
        boolean needAddMargin = gap > 0;
        event.setNeedAddMargin(needAddMargin);
        event.setSuggestedAddAmount(needAddMargin ? gap * 2 : 0L);

        event.setTimestamp(System.currentTimeMillis());
        event.setSendNotification(sendNotification);

        // 构建预警消息
        String message = String.format("全仓账户风险预警 [%s]：保证金率=%.2f%%, 当前保证金=%d, " +
                        "未实现盈亏=%d, 维持保证金=%d",
                riskLevel, marginRatio / 100.0, snapshot.getCrossMargin(),
                snapshot.getTotalUnrealizedPnl(), totalMaintenanceMargin);

        if (needAddMargin) {
            message += String.format(", 建议追加保证金=%d", event.getSuggestedAddAmount());
        }

        event.setMessage(message);

        publishEvent(event);
    }

    /**
     * 发布保证金率恢复预警（从危险恢复到安全）
     *
     * @param position 仓位详情
     * @param previousRiskLevel 之前的风险等级
     */
    public void publishRiskRecoveryWarning(PositionMarginDetail position, String previousRiskLevel) {
        RiskWarningEvent event = new RiskWarningEvent();
        event.setUserId(position.getUserId());
        event.setPositionId(position.getPositionId());
        event.setSymbol(position.getSymbol());
        event.setMarginMode(position.getMarginMode());
        event.setRiskLevel("SAFE");

        event.setMarginRatio(position.getMarginRatio());
        event.setCurrentMargin(position.getIsolatedMargin());
        event.setUnrealizedPnl(position.getUnrealizedPnl());
        event.setMarkPrice(position.getMarkPrice());
        event.setLiquidationPrice(position.getLiquidationPrice());
        event.setLeverage(position.getLeverage());

        event.setNeedAddMargin(false);
        event.setSuggestedAddAmount(0L);
        event.setTimestamp(System.currentTimeMillis());
        event.setSendNotification(true);

        String message = String.format("风险恢复通知：仓位已从 [%s] 恢复到安全状态，" +
                        "交易对=%s, 保证金率=%.2f%%",
                previousRiskLevel, position.getSymbol(), position.getMarginRatio() / 100.0);

        event.setMessage(message);

        publishEvent(event);
    }

    /**
     * 确定风险等级
     *
     * @param marginRatio 保证金率（万分比）
     * @return 风险等级
     */
    private String determineRiskLevel(Long marginRatio) {
        if (marginRatio == null || marginRatio < CRITICAL_THRESHOLD) {
            return "LIQUIDATION";
        } else if (marginRatio < DANGER_THRESHOLD) {
            return "CRITICAL";
        } else if (marginRatio < WARNING_THRESHOLD) {
            return "DANGER";
        } else if (marginRatio < SAFE_THRESHOLD) {
            return "WARNING";
        } else {
            return "SAFE";
        }
    }

    /**
     * 判断是否需要发送通知
     *
     * @param marginRatio 保证金率（万分比）
     * @return 是否发送通知
     */
    private boolean shouldSendNotification(Long marginRatio) {
        // 危险、紧急、强平级别发送通知，警告级别不发送
        return marginRatio != null && marginRatio < DANGER_THRESHOLD;
    }

    /**
     * 发布事件到Kafka
     *
     * @param event 风险预警事件
     */
    private void publishEvent(RiskWarningEvent event) {
        try {
            kafkaTemplate.send(RISK_WARNING_TOPIC, event.getUserId().toString(), event);

            log.warn("[RiskWarningProducer] Published risk warning event: userId={}, positionId={}, " +
                            "symbol={}, marginMode={}, riskLevel={}, marginRatio={}%, " +
                            "needAddMargin={}, sendNotification={}",
                    event.getUserId(), event.getPositionId(), event.getSymbol(),
                    event.getMarginMode(), event.getRiskLevel(), event.getMarginRatio() / 100.0,
                    event.getNeedAddMargin(), event.getSendNotification());

        } catch (Exception e) {
            log.error("[RiskWarningProducer] Failed to publish risk warning event: userId={}, " +
                            "positionId={}, riskLevel={}",
                    event.getUserId(), event.getPositionId(), event.getRiskLevel(), e);
        }
    }
}
