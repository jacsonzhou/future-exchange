package com.exchange.margin.consumer;

import com.exchange.margin.calculator.MarginCalculator;
import com.exchange.margin.dto.AccountBalanceEvent;
import com.exchange.margin.dto.RiskWarningEvent;
import com.exchange.margin.entity.CrossMarginSnapshot;
import com.exchange.margin.entity.PositionMarginDetail;
import com.exchange.margin.mapper.CrossMarginSnapshotMapper;
import com.exchange.margin.mapper.PositionMarginDetailMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 账户余额变动消费者
 *
 * 消费 account-change-topic，更新全仓保证金快照并检查风险
 */
@Slf4j
@Component
public class AccountBalanceConsumer {

    private final CrossMarginSnapshotMapper snapshotMapper;
    private final PositionMarginDetailMapper positionMapper;
    private final MarginCalculator marginCalculator;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    // 风险预警topic
    private static final String RISK_WARNING_TOPIC = "risk-warning-topic";

    // 风险阈值（万分比）
    private static final long SAFE_THRESHOLD = 10000L;      // 100%
    private static final long WARNING_THRESHOLD = 5000L;     // 50%
    private static final long DANGER_THRESHOLD = 2000L;      // 20%
    private static final long CRITICAL_THRESHOLD = 1000L;    // 10%
    private static final long LIQUIDATION_THRESHOLD = 1000L; // 10%

    public AccountBalanceConsumer(CrossMarginSnapshotMapper snapshotMapper,
                                  PositionMarginDetailMapper positionMapper,
                                  MarginCalculator marginCalculator,
                                  KafkaTemplate<String, Object> kafkaTemplate) {
        this.snapshotMapper = snapshotMapper;
        this.positionMapper = positionMapper;
        this.marginCalculator = marginCalculator;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * 消费账户余额变动事件
     *
     * @param event 账户余额事件
     */
    @KafkaListener(topics = "account-change-topic", groupId = "margin-mode-service")
    @Transactional
    public void consumeAccountBalanceEvent(AccountBalanceEvent event) {
        try {
            log.info("[AccountBalanceConsumer] Received account balance event, userId={}, " +
                            "changeType={}, amount={}, availableBalance={}, timestamp={}",
                    event.getUserId(), event.getChangeType(), event.getAmount(),
                    event.getAvailableBalance(), event.getTimestamp());

            // 1. 查询该用户的全仓保证金快照
            CrossMarginSnapshot snapshot = snapshotMapper.selectByUserId(event.getUserId());

            if (snapshot == null) {
                // 如果快照不存在，可能是新用户或还没创建全仓仓位，跳过
                log.debug("[AccountBalanceConsumer] No cross margin snapshot found for userId={}",
                        event.getUserId());
                return;
            }

            // 2. 更新快照的账户余额信息
            snapshot.setTotalBalance(event.getTotalBalance());
            snapshot.setAvailableBalance(event.getAvailableBalance());
            snapshot.setFrozenBalance(event.getFrozenBalance());
            snapshot.setUpdatedAt(System.currentTimeMillis());

            // 3. 查询用户所有全仓仓位，重新计算总保证金和风险指标
            List<PositionMarginDetail> crossPositions = positionMapper.selectCrossPositions(event.getUserId());

            if (crossPositions != null && !crossPositions.isEmpty()) {
                // 计算全仓总未实现盈亏
                long totalUnrealizedPnl = crossPositions.stream()
                        .mapToLong(p -> p.getUnrealizedPnl() != null ? p.getUnrealizedPnl() : 0L)
                        .sum();

                // 计算全仓总仓位价值
                long totalPositionValue = crossPositions.stream()
                        .mapToLong(p -> p.getPositionValue() != null ? p.getPositionValue() : 0L)
                        .sum();

                // 全仓保证金 = 可用余额 + 冻结余额
                long crossMargin = event.getAvailableBalance() + event.getFrozenBalance();

                // 计算全仓保证金率
                Long marginRatio = marginCalculator.calculateMarginRatio(
                        crossMargin,
                        totalUnrealizedPnl,
                        totalPositionValue
                );

                // 更新快照
                snapshot.setTotalUnrealizedPnl(totalUnrealizedPnl);
                snapshot.setTotalPositionValue(totalPositionValue);
                snapshot.setCrossMargin(crossMargin);
                snapshot.setMarginRatio(marginRatio);

                // 更新到数据库
                int updated = snapshotMapper.updateById(snapshot);

                if (updated > 0) {
                    log.info("[AccountBalanceConsumer] Cross margin snapshot updated, userId={}, " +
                                    "crossMargin={}, marginRatio={}%, totalPositionValue={}, unrealizedPnl={}",
                            event.getUserId(), crossMargin, marginRatio / 100.0,
                            totalPositionValue, totalUnrealizedPnl);

                    // 检查风险等级并发送预警
                    checkRiskAndSendWarning(snapshot, event);
                } else {
                    log.warn("[AccountBalanceConsumer] Failed to update snapshot (optimistic lock), userId={}",
                            event.getUserId());
                }
            } else {
                // 没有全仓仓位，仅更新余额信息
                int updated = snapshotMapper.updateById(snapshot);
                log.debug("[AccountBalanceConsumer] No cross positions, snapshot balance updated, userId={}, updated={}",
                        event.getUserId(), updated);
            }

        } catch (Exception e) {
            log.error("[AccountBalanceConsumer] Failed to consume account balance event, userId={}",
                    event.getUserId(), e);
            // 不抛异常，避免消息重试
        }
    }

    /**
     * 检查风险等级并发送预警
     *
     * @param snapshot 全仓快照
     * @param event 账户余额事件
     */
    private void checkRiskAndSendWarning(CrossMarginSnapshot snapshot, AccountBalanceEvent event) {
        Long marginRatio = snapshot.getMarginRatio();

        if (marginRatio == null || marginRatio >= SAFE_THRESHOLD) {
            // 安全区域，无需预警
            return;
        }

        // 确定风险等级
        String riskLevel;
        boolean sendNotification;

        if (marginRatio < LIQUIDATION_THRESHOLD) {
            riskLevel = "LIQUIDATION";
            sendNotification = true;
        } else if (marginRatio < CRITICAL_THRESHOLD) {
            riskLevel = "CRITICAL";
            sendNotification = true;
        } else if (marginRatio < DANGER_THRESHOLD) {
            riskLevel = "DANGER";
            sendNotification = true;
        } else if (marginRatio < WARNING_THRESHOLD) {
            riskLevel = "WARNING";
            sendNotification = false; // 警告级别不发送通知，只记录
        } else {
            return; // 安全区域
        }

        // 构建风险预警事件
        RiskWarningEvent warningEvent = new RiskWarningEvent();
        warningEvent.setUserId(snapshot.getUserId());
        warningEvent.setPositionId(null); // 全仓没有单独的仓位ID
        warningEvent.setSymbol(null);     // 全仓涵盖所有交易对
        warningEvent.setMarginMode("CROSS");
        warningEvent.setRiskLevel(riskLevel);

        warningEvent.setMarginRatio(marginRatio);
        warningEvent.setCurrentMargin(snapshot.getCrossMargin());

        // 计算全仓总维持保证金
        long totalMaintenanceMargin = marginCalculator.calculateMaintenanceMargin(
                snapshot.getTotalPositionValue(),
                50L // 默认维持保证金率0.5% = 50（万分比）
        );
        warningEvent.setMaintenanceMargin(totalMaintenanceMargin);

        warningEvent.setUnrealizedPnl(snapshot.getTotalUnrealizedPnl());
        warningEvent.setMarkPrice(null); // 全仓没有单一标记价格
        warningEvent.setLiquidationPrice(null); // 全仓没有单一强平价
        warningEvent.setLiquidationGap(null);
        warningEvent.setLiquidationGapPercent(null);
        warningEvent.setLeverage(null); // 全仓不是单一杠杆

        // 判断是否需要追加保证金
        long gap = totalMaintenanceMargin - (snapshot.getCrossMargin() + snapshot.getTotalUnrealizedPnl());
        boolean needAddMargin = gap > 0;
        warningEvent.setNeedAddMargin(needAddMargin);
        warningEvent.setSuggestedAddAmount(needAddMargin ? gap * 2 : 0L); // 建议追加2倍缺口

        warningEvent.setTimestamp(System.currentTimeMillis());
        warningEvent.setSendNotification(sendNotification);

        // 构建预警消息
        String message = String.format("全仓账户风险预警 [%s]：保证金率=%.2f%%, 当前保证金=%d, " +
                        "未实现盈亏=%d, 维持保证金=%d",
                riskLevel, marginRatio / 100.0, snapshot.getCrossMargin(),
                snapshot.getTotalUnrealizedPnl(), totalMaintenanceMargin);

        if (needAddMargin) {
            message += String.format(", 建议追加保证金=%d", warningEvent.getSuggestedAddAmount());
        }

        warningEvent.setMessage(message);

        // 发送到Kafka
        kafkaTemplate.send(RISK_WARNING_TOPIC, warningEvent);

        log.warn("[AccountBalanceConsumer] Risk warning sent! userId={}, riskLevel={}, " +
                        "marginRatio={}%, needAddMargin={}, suggestedAmount={}",
                snapshot.getUserId(), riskLevel, marginRatio / 100.0,
                needAddMargin, warningEvent.getSuggestedAddAmount());
    }
}
