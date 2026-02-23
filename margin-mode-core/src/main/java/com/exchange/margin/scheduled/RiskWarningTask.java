package com.exchange.margin.scheduled;

import com.exchange.margin.entity.CrossMarginSnapshot;
import com.exchange.margin.entity.PositionMarginDetail;
import com.exchange.margin.mapper.CrossMarginSnapshotMapper;
import com.exchange.margin.mapper.PositionMarginDetailMapper;
import com.exchange.margin.producer.RiskWarningProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 风险预警定时任务
 *
 * 定期检测高风险账户和仓位，发送风险预警，防止重复预警
 */
@Slf4j
@Component
public class RiskWarningTask {

    private final PositionMarginDetailMapper positionMapper;
    private final CrossMarginSnapshotMapper snapshotMapper;
    private final RiskWarningProducer riskWarningProducer;

    @Value("${margin.risk.safe-threshold:10000}")
    private long safeThreshold;

    @Value("${margin.risk.warning-threshold:5000}")
    private long warningThreshold;

    @Value("${margin.risk.danger-threshold:2000}")
    private long dangerThreshold;

    @Value("${margin.risk.liquidation-threshold:1000}")
    private long liquidationThreshold;

    // 用于防止重复预警的缓存
    // Key: positionId or userId, Value: last warning risk level
    private final Map<String, String> lastWarningCache = new ConcurrentHashMap<>();

    // 预警间隔（毫秒），相同风险等级不重复发送
    private static final long WARNING_INTERVAL = 60000; // 1分钟

    public RiskWarningTask(PositionMarginDetailMapper positionMapper,
                           CrossMarginSnapshotMapper snapshotMapper,
                           RiskWarningProducer riskWarningProducer) {
        this.positionMapper = positionMapper;
        this.snapshotMapper = snapshotMapper;
        this.riskWarningProducer = riskWarningProducer;
    }

    /**
     * 定时风险预警检测
     *
     * 默认每5秒执行一次
     */
    @Scheduled(fixedDelay = 5000)
    public void checkRiskWarning() {
        try {
            long startTime = System.currentTimeMillis();

            // 1. 检查逐仓仓位风险
            int isolatedWarnings = checkIsolatedPositionRisk();

            // 2. 检查全仓账户风险
            int crossWarnings = checkCrossAccountRisk();

            long duration = System.currentTimeMillis() - startTime;

            if (isolatedWarnings > 0 || crossWarnings > 0) {
                log.info("[RiskWarningTask] Risk warning check completed, " +
                                "isolatedWarnings={}, crossWarnings={}, duration={}ms",
                        isolatedWarnings, crossWarnings, duration);
            } else {
                log.debug("[RiskWarningTask] Risk warning check completed, " +
                        "no warnings triggered, duration={}ms", duration);
            }

        } catch (Exception e) {
            log.error("[RiskWarningTask] Risk warning task failed", e);
        }
    }

    /**
     * 检查逐仓仓位风险
     *
     * @return 发送的预警数量
     */
    private int checkIsolatedPositionRisk() {
        // 查询高风险的逐仓仓位（保证金率低于安全阈值）
        List<PositionMarginDetail> riskPositions =
                positionMapper.selectHighRiskIsolatedPositions(safeThreshold);

        if (riskPositions == null || riskPositions.isEmpty()) {
            return 0;
        }

        int warningCount = 0;

        for (PositionMarginDetail position : riskPositions) {
            try {
                // 确定风险等级
                String riskLevel = determineRiskLevel(position.getMarginRatio());

                // 检查是否需要发送预警（防止重复）
                String cacheKey = "isolated_" + position.getPositionId();
                String lastRiskLevel = lastWarningCache.get(cacheKey);

                if (shouldSendWarning(riskLevel, lastRiskLevel)) {
                    // 发布风险预警
                    riskWarningProducer.publishIsolatedRiskWarning(
                            position, position.getMarkPrice()
                    );

                    // 更新缓存
                    lastWarningCache.put(cacheKey, riskLevel);
                    warningCount++;

                    log.warn("[RiskWarningTask] Isolated position risk warning sent, " +
                                    "positionId={}, symbol={}, marginRatio={}%, riskLevel={}",
                            position.getPositionId(), position.getSymbol(),
                            position.getMarginRatio() / 100.0, riskLevel);
                }

            } catch (Exception e) {
                log.error("[RiskWarningTask] Failed to send risk warning for isolated position, " +
                        "positionId={}", position.getPositionId(), e);
            }
        }

        return warningCount;
    }

    /**
     * 检查全仓账户风险
     *
     * @return 发送的预警数量
     */
    private int checkCrossAccountRisk() {
        // 查询高风险的全仓账户（保证金率低于安全阈值）
        List<CrossMarginSnapshot> riskSnapshots =
                snapshotMapper.selectHighRiskUsers(safeThreshold);

        if (riskSnapshots == null || riskSnapshots.isEmpty()) {
            return 0;
        }

        int warningCount = 0;

        for (CrossMarginSnapshot snapshot : riskSnapshots) {
            try {
                // 确定风险等级
                String riskLevel = determineRiskLevel(snapshot.getMarginRatio());

                // 检查是否需要发送预警（防止重复）
                String cacheKey = "cross_" + snapshot.getUserId();
                String lastRiskLevel = lastWarningCache.get(cacheKey);

                if (shouldSendWarning(riskLevel, lastRiskLevel)) {
                    // 发布风险预警
                    riskWarningProducer.publishCrossRiskWarning(snapshot);

                    // 更新缓存
                    lastWarningCache.put(cacheKey, riskLevel);
                    warningCount++;

                    log.warn("[RiskWarningTask] Cross account risk warning sent, " +
                                    "userId={}, marginRatio={}%, riskLevel={}",
                            snapshot.getUserId(), snapshot.getMarginRatio() / 100.0, riskLevel);
                }

            } catch (Exception e) {
                log.error("[RiskWarningTask] Failed to send risk warning for cross account, " +
                        "userId={}", snapshot.getUserId(), e);
            }
        }

        return warningCount;
    }

    /**
     * 确定风险等级
     *
     * @param marginRatio 保证金率（万分比）
     * @return 风险等级
     */
    private String determineRiskLevel(Long marginRatio) {
        if (marginRatio == null || marginRatio < liquidationThreshold) {
            return "LIQUIDATION";
        } else if (marginRatio < dangerThreshold) {
            return "CRITICAL";
        } else if (marginRatio < warningThreshold) {
            return "DANGER";
        } else if (marginRatio < safeThreshold) {
            return "WARNING";
        } else {
            return "SAFE";
        }
    }

    /**
     * 判断是否需要发送预警
     *
     * @param currentRiskLevel 当前风险等级
     * @param lastRiskLevel 上次风险等级
     * @return 是否发送预警
     */
    private boolean shouldSendWarning(String currentRiskLevel, String lastRiskLevel) {
        // 如果是第一次检测，发送预警
        if (lastRiskLevel == null) {
            return true;
        }

        // 如果风险等级变化，发送预警
        if (!currentRiskLevel.equals(lastRiskLevel)) {
            return true;
        }

        // 如果是CRITICAL或LIQUIDATION级别，即使相同也发送（每次检测都发送）
        if ("CRITICAL".equals(currentRiskLevel) || "LIQUIDATION".equals(currentRiskLevel)) {
            return true;
        }

        // 其他情况，相同风险等级不重复发送
        return false;
    }

    /**
     * 清理风险预警缓存（定期清理，防止内存泄漏）
     *
     * 每小时执行一次
     */
    @Scheduled(fixedDelay = 3600000)
    public void cleanWarningCache() {
        try {
            int sizeBefore = lastWarningCache.size();

            // 清空缓存（简单实现，生产环境可以使用LRU缓存）
            lastWarningCache.clear();

            log.info("[RiskWarningTask] Warning cache cleaned, sizeBefore={}", sizeBefore);

        } catch (Exception e) {
            log.error("[RiskWarningTask] Failed to clean warning cache", e);
        }
    }

    /**
     * 手动触发风险预警检查（供外部调用）
     *
     * @param positionId 仓位ID
     * @return 是否发送预警
     */
    public boolean checkPositionRisk(Long positionId) {
        try {
            PositionMarginDetail position = positionMapper.selectByPositionId(positionId);
            if (position == null) {
                log.warn("[RiskWarningTask] Position not found, positionId={}", positionId);
                return false;
            }

            if (position.getMarginRatio() != null && position.getMarginRatio() < safeThreshold) {
                String riskLevel = determineRiskLevel(position.getMarginRatio());
                riskWarningProducer.publishIsolatedRiskWarning(position, position.getMarkPrice());

                log.warn("[RiskWarningTask] Manual risk warning sent, positionId={}, " +
                                "marginRatio={}%, riskLevel={}",
                        positionId, position.getMarginRatio() / 100.0, riskLevel);
                return true;
            }

            return false;
        } catch (Exception e) {
            log.error("[RiskWarningTask] Failed to check position risk manually, positionId={}",
                    positionId, e);
            return false;
        }
    }

    /**
     * 手动触发全仓账户风险预警检查（供外部调用）
     *
     * @param userId 用户ID
     * @return 是否发送预警
     */
    public boolean checkCrossAccountRisk(Long userId) {
        try {
            CrossMarginSnapshot snapshot = snapshotMapper.selectByUserId(userId);
            if (snapshot == null) {
                log.warn("[RiskWarningTask] Snapshot not found, userId={}", userId);
                return false;
            }

            if (snapshot.getMarginRatio() != null && snapshot.getMarginRatio() < safeThreshold) {
                String riskLevel = determineRiskLevel(snapshot.getMarginRatio());
                riskWarningProducer.publishCrossRiskWarning(snapshot);

                log.warn("[RiskWarningTask] Manual cross risk warning sent, userId={}, " +
                                "marginRatio={}%, riskLevel={}",
                        userId, snapshot.getMarginRatio() / 100.0, riskLevel);
                return true;
            }

            return false;
        } catch (Exception e) {
            log.error("[RiskWarningTask] Failed to check cross account risk manually, userId={}",
                    userId, e);
            return false;
        }
    }
}
