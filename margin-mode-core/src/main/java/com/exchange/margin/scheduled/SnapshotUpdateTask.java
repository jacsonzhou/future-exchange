package com.exchange.margin.scheduled;

import com.exchange.margin.calculator.MarginCalculator;
import com.exchange.margin.entity.CrossMarginSnapshot;
import com.exchange.margin.entity.PositionMarginDetail;
import com.exchange.margin.mapper.CrossMarginSnapshotMapper;
import com.exchange.margin.mapper.PositionMarginDetailMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全仓保证金快照自动更新定时任务
 *
 * 定期更新全仓账户的保证金快照，计算总未实现盈亏、总仓位价值、保证金率等指标
 */
@Slf4j
@Component
public class SnapshotUpdateTask {

    private final CrossMarginSnapshotMapper snapshotMapper;
    private final PositionMarginDetailMapper positionMapper;
    private final MarginCalculator marginCalculator;

    @Value("${margin.snapshot.auto-update:true}")
    private boolean autoUpdate;

    @Value("${margin.snapshot.batch-size:100}")
    private int batchSize;

    public SnapshotUpdateTask(CrossMarginSnapshotMapper snapshotMapper,
                              PositionMarginDetailMapper positionMapper,
                              MarginCalculator marginCalculator) {
        this.snapshotMapper = snapshotMapper;
        this.positionMapper = positionMapper;
        this.marginCalculator = marginCalculator;
    }

    /**
     * 定时更新全仓快照
     *
     * 默认每1秒执行一次（配置：margin.snapshot.update-interval）
     */
    @Scheduled(fixedDelayString = "${margin.snapshot.update-interval:1000}")
    public void updateCrossMarginSnapshots() {
        if (!autoUpdate) {
            return;
        }

        try {
            long startTime = System.currentTimeMillis();

            // 查询所有需要更新的全仓快照
            List<CrossMarginSnapshot> snapshots = snapshotMapper.selectAll();

            if (snapshots == null || snapshots.isEmpty()) {
                log.debug("[SnapshotUpdateTask] No snapshots to update");
                return;
            }

            int totalUpdated = 0;
            int totalFailed = 0;

            // 批量更新
            for (int i = 0; i < snapshots.size(); i += batchSize) {
                int end = Math.min(i + batchSize, snapshots.size());
                List<CrossMarginSnapshot> batch = snapshots.subList(i, end);

                for (CrossMarginSnapshot snapshot : batch) {
                    try {
                        boolean updated = updateSnapshot(snapshot);
                        if (updated) {
                            totalUpdated++;
                        } else {
                            totalFailed++;
                        }
                    } catch (Exception e) {
                        log.error("[SnapshotUpdateTask] Failed to update snapshot, userId={}",
                                snapshot.getUserId(), e);
                        totalFailed++;
                    }
                }
            }

            long duration = System.currentTimeMillis() - startTime;

            log.info("[SnapshotUpdateTask] Snapshot update completed, total={}, updated={}, " +
                            "failed={}, duration={}ms",
                    snapshots.size(), totalUpdated, totalFailed, duration);

        } catch (Exception e) {
            log.error("[SnapshotUpdateTask] Snapshot update task failed", e);
        }
    }

    /**
     * 更新单个快照
     *
     * @param snapshot 快照
     * @return 是否更新成功
     */
    private boolean updateSnapshot(CrossMarginSnapshot snapshot) {
        Long userId = snapshot.getUserId();

        // 查询用户所有全仓仓位
        List<PositionMarginDetail> crossPositions = positionMapper.selectCrossPositions(userId);

        if (crossPositions == null || crossPositions.isEmpty()) {
            // 没有全仓仓位，清空相关字段
            snapshot.setTotalUnrealizedPnl(0L);
            snapshot.setTotalPositionValue(0L);
            snapshot.setMarginRatio(null);
            snapshot.setUpdatedAt(System.currentTimeMillis());

            int updated = snapshotMapper.updateById(snapshot);
            return updated > 0;
        }

        // 计算总未实现盈亏
        long totalUnrealizedPnl = crossPositions.stream()
                .mapToLong(p -> p.getUnrealizedPnl() != null ? p.getUnrealizedPnl() : 0L)
                .sum();

        // 计算总仓位价值
        long totalPositionValue = crossPositions.stream()
                .mapToLong(p -> p.getPositionValue() != null ? p.getPositionValue() : 0L)
                .sum();

        // 计算总持仓数
        int totalPositions = crossPositions.size();

        // 全仓保证金 = 可用余额 + 冻结余额（账户余额）
        long crossMargin = snapshot.getAvailableBalance() + snapshot.getFrozenBalance();

        // 计算保证金率
        Long marginRatio = marginCalculator.calculateMarginRatio(
                crossMargin,
                totalUnrealizedPnl,
                totalPositionValue
        );

        // 更新快照
        snapshot.setTotalUnrealizedPnl(totalUnrealizedPnl);
        snapshot.setTotalPositionValue(totalPositionValue);
        snapshot.setTotalPositions(totalPositions);
        snapshot.setCrossMargin(crossMargin);
        snapshot.setMarginRatio(marginRatio);
        snapshot.setUpdatedAt(System.currentTimeMillis());

        // 按交易对统计
        Map<String, Long> positionValueBySymbol = crossPositions.stream()
                .collect(Collectors.groupingBy(
                        PositionMarginDetail::getSymbol,
                        Collectors.summingLong(p -> p.getPositionValue() != null ? p.getPositionValue() : 0L)
                ));

        Map<String, Long> unrealizedPnlBySymbol = crossPositions.stream()
                .collect(Collectors.groupingBy(
                        PositionMarginDetail::getSymbol,
                        Collectors.summingLong(p -> p.getUnrealizedPnl() != null ? p.getUnrealizedPnl() : 0L)
                ));

        // 更新到数据库（使用乐观锁）
        int updated = snapshotMapper.updateById(snapshot);

        if (updated > 0) {
            log.debug("[SnapshotUpdateTask] Snapshot updated, userId={}, crossMargin={}, " +
                            "marginRatio={}%, totalPositionValue={}, totalUnrealizedPnl={}, totalPositions={}",
                    userId, crossMargin, marginRatio != null ? marginRatio / 100.0 : null,
                    totalPositionValue, totalUnrealizedPnl, totalPositions);
        } else {
            log.warn("[SnapshotUpdateTask] Failed to update snapshot (optimistic lock), userId={}, version={}",
                    userId, snapshot.getVersion());
        }

        return updated > 0;
    }

    /**
     * 手动触发快照更新（供外部调用）
     *
     * @param userId 用户ID
     * @return 是否更新成功
     */
    public boolean updateSnapshotByUserId(Long userId) {
        try {
            CrossMarginSnapshot snapshot = snapshotMapper.selectByUserId(userId);
            if (snapshot == null) {
                log.warn("[SnapshotUpdateTask] Snapshot not found, userId={}", userId);
                return false;
            }

            return updateSnapshot(snapshot);
        } catch (Exception e) {
            log.error("[SnapshotUpdateTask] Failed to update snapshot manually, userId={}", userId, e);
            return false;
        }
    }
}
