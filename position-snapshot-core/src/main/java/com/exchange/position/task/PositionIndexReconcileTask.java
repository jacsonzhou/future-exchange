package com.exchange.position.task;

import com.exchange.position.entity.PositionSnapshot;
import com.exchange.position.mapper.PositionSnapshotMapper;
import com.exchange.position.service.support.ActivePositionIndex;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 内存索引与数据库持仓快照对账任务。
 */
@Slf4j
@Component
public class PositionIndexReconcileTask {

    private final ActivePositionIndex activePositionIndex;
    private final PositionSnapshotMapper positionSnapshotMapper;

    public PositionIndexReconcileTask(
        ActivePositionIndex activePositionIndex,
        PositionSnapshotMapper positionSnapshotMapper
    ) {
        this.activePositionIndex = activePositionIndex;
        this.positionSnapshotMapper = positionSnapshotMapper;
    }

    @Scheduled(
        initialDelayString = "${position.index.reconcile-initial-delay-ms:30000}",
        fixedDelayString = "${position.index.reconcile-interval-ms:60000}"
    )
    public void reconcile() {
        try {
            List<String> activeSymbolsInDb = positionSnapshotMapper.selectActiveSymbols();
            Set<String> targetSymbols = new HashSet<>();

            if (activeSymbolsInDb != null) {
                for (String symbol : activeSymbolsInDb) {
                    if (symbol == null || symbol.isBlank()) {
                        continue;
                    }
                    targetSymbols.add(symbol.trim().toUpperCase());
                }
            }

            targetSymbols.addAll(activePositionIndex.getTrackedSymbols());

            int refreshed = 0;
            for (String symbol : targetSymbols) {
                List<PositionSnapshot> snapshots = positionSnapshotMapper.selectBySymbol(symbol);
                activePositionIndex.refreshSymbol(symbol, snapshots);
                refreshed++;
            }

            log.debug("[PositionIndexReconcileTask] Reconciled symbols={}, indexStats={}",
                refreshed, activePositionIndex.getSymbolPositionCounts());
        } catch (Exception e) {
            log.error("[PositionIndexReconcileTask] Reconcile failed", e);
        }
    }
}
