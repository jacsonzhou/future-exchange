package com.exchange.position.service.support;

import com.exchange.position.entity.PositionSnapshot;
import com.exchange.position.mapper.PositionSnapshotMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Symbol -> Active Positions 内存索引。
 *
 * 目标：
 * 1) mark-price 更新时避免反复全表/大范围扫描
 * 2) 只处理对应 symbol 的活跃仓位
 * 3) 通过定时对账维持缓存与 DB 一致
 */
@Slf4j
@Component
public class ActivePositionIndex {

    @Autowired
    private PositionSnapshotMapper positionSnapshotMapper;

    @Value("${position.index.preload-enabled:true}")
    private boolean preloadEnabled;

    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, PositionSnapshot>> positionsBySymbol =
        new ConcurrentHashMap<>();
    private final Set<String> loadedSymbols = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Object> symbolLoadLocks = new ConcurrentHashMap<>();

    @PostConstruct
    public void preload() {
        if (!preloadEnabled) {
            log.info("[ActivePositionIndex] Preload disabled");
            return;
        }

        try {
            List<PositionSnapshot> allActive = positionSnapshotMapper.selectAllActive();
            if (allActive == null || allActive.isEmpty()) {
                log.info("[ActivePositionIndex] Preload completed, activePositions=0");
                return;
            }

            ConcurrentHashMap<String, ConcurrentHashMap<Long, PositionSnapshot>> grouped = new ConcurrentHashMap<>();
            for (PositionSnapshot snapshot : allActive) {
                if (snapshot == null || snapshot.getId() == null || !snapshot.hasPosition()) {
                    continue;
                }
                String symbol = normalizeSymbol(snapshot.getSymbol());
                if (symbol.isBlank()) {
                    continue;
                }
                grouped
                    .computeIfAbsent(symbol, key -> new ConcurrentHashMap<>())
                    .put(snapshot.getId(), copyOf(snapshot));
            }

            positionsBySymbol.clear();
            positionsBySymbol.putAll(grouped);

            loadedSymbols.clear();
            loadedSymbols.addAll(grouped.keySet());

            int totalPositions = grouped.values().stream().mapToInt(Map::size).sum();
            log.info("[ActivePositionIndex] Preload completed, symbols={}, activePositions={}",
                grouped.size(), totalPositions);
        } catch (Exception e) {
            log.error("[ActivePositionIndex] Preload failed, fallback to lazy-load", e);
        }
    }

    public List<PositionSnapshot> getActivePositions(String symbol) {
        String normalized = normalizeSymbol(symbol);
        if (normalized.isBlank()) {
            return List.of();
        }

        ensureSymbolLoaded(normalized);
        Map<Long, PositionSnapshot> symbolPositions = positionsBySymbol.get(normalized);
        if (symbolPositions == null || symbolPositions.isEmpty()) {
            return List.of();
        }

        List<PositionSnapshot> snapshots = new ArrayList<>(symbolPositions.size());
        for (PositionSnapshot snapshot : symbolPositions.values()) {
            snapshots.add(copyOf(snapshot));
        }
        return snapshots;
    }

    public void upsert(PositionSnapshot snapshot) {
        if (snapshot == null || snapshot.getId() == null) {
            return;
        }
        String symbol = normalizeSymbol(snapshot.getSymbol());
        if (symbol.isBlank()) {
            return;
        }

        if (snapshot.hasPosition()) {
            positionsBySymbol
                .computeIfAbsent(symbol, key -> new ConcurrentHashMap<>())
                .put(snapshot.getId(), copyOf(snapshot));
        } else {
            remove(snapshot.getId(), symbol);
        }

        loadedSymbols.add(symbol);
    }

    public void remove(Long positionId, String symbol) {
        if (positionId == null) {
            return;
        }
        String normalized = normalizeSymbol(symbol);
        if (normalized.isBlank()) {
            return;
        }

        Map<Long, PositionSnapshot> symbolPositions = positionsBySymbol.get(normalized);
        if (symbolPositions == null) {
            loadedSymbols.add(normalized);
            return;
        }

        symbolPositions.remove(positionId);
        if (symbolPositions.isEmpty()) {
            positionsBySymbol.remove(normalized);
        }
        loadedSymbols.add(normalized);
    }

    public void refreshSymbol(String symbol, List<PositionSnapshot> dbSnapshots) {
        String normalized = normalizeSymbol(symbol);
        if (normalized.isBlank()) {
            return;
        }

        ConcurrentHashMap<Long, PositionSnapshot> refreshed = new ConcurrentHashMap<>();
        if (dbSnapshots != null) {
            for (PositionSnapshot snapshot : dbSnapshots) {
                if (snapshot == null || snapshot.getId() == null || !snapshot.hasPosition()) {
                    continue;
                }
                refreshed.put(snapshot.getId(), copyOf(snapshot));
            }
        }

        if (refreshed.isEmpty()) {
            positionsBySymbol.remove(normalized);
        } else {
            positionsBySymbol.put(normalized, refreshed);
        }
        loadedSymbols.add(normalized);
    }

    public Set<String> getTrackedSymbols() {
        return new HashSet<>(positionsBySymbol.keySet());
    }

    public Map<String, Integer> getSymbolPositionCounts() {
        Map<String, Integer> stats = new ConcurrentHashMap<>();
        positionsBySymbol.forEach((symbol, positions) -> stats.put(symbol, positions.size()));
        return Collections.unmodifiableMap(stats);
    }

    private void ensureSymbolLoaded(String symbol) {
        if (loadedSymbols.contains(symbol)) {
            return;
        }

        Object lock = symbolLoadLocks.computeIfAbsent(symbol, key -> new Object());
        synchronized (lock) {
            try {
                if (loadedSymbols.contains(symbol)) {
                    return;
                }
                List<PositionSnapshot> snapshots = positionSnapshotMapper.selectBySymbol(symbol);
                refreshSymbol(symbol, snapshots);
                log.debug("[ActivePositionIndex] Loaded symbol={}, size={}", symbol, snapshots == null ? 0 : snapshots.size());
            } finally {
                loadedSymbols.add(symbol);
                symbolLoadLocks.remove(symbol);
            }
        }
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase();
    }

    private PositionSnapshot copyOf(PositionSnapshot src) {
        if (src == null) {
            return null;
        }
        PositionSnapshot copy = new PositionSnapshot();
        copy.setId(src.getId());
        copy.setUserId(src.getUserId());
        copy.setSymbol(normalizeSymbol(src.getSymbol()));
        copy.setPositionSide(src.getPositionSide());
        copy.setSize(src.getSize());
        copy.setEntryPrice(src.getEntryPrice());
        copy.setUnrealizedPnl(src.getUnrealizedPnl());
        copy.setRealizedPnl(src.getRealizedPnl());
        copy.setMarginRatio(src.getMarginRatio());
        copy.setLiquidationPrice(src.getLiquidationPrice());
        copy.setLastTradeId(src.getLastTradeId());
        copy.setLastMarkPriceId(src.getLastMarkPriceId());
        copy.setLastUpdateSeq(src.getLastUpdateSeq());
        copy.setVersion(src.getVersion());
        copy.setCreatedAt(src.getCreatedAt());
        copy.setUpdatedAt(src.getUpdatedAt());
        return copy;
    }
}
