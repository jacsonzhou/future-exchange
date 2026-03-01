package com.exchange.position.controller;

import com.exchange.common.core.Result;
import com.exchange.position.entity.PositionSnapshot;
import com.exchange.position.mapper.PositionSnapshotMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ADL对接持仓契约：
 * 1) 查询盈利候选仓位
 * 2) 根据持仓ID查询
 * 3) ADL执行通知回执
 */
@Slf4j
@RestController
public class PositionAdlController {

    private static final BigDecimal DEFAULT_LEVERAGE = new BigDecimal("10");
    private static final BigDecimal MAINTENANCE_MARGIN_RATE = new BigDecimal("0.005");
    private static final BigDecimal SCORE_FACTOR = new BigDecimal("10000");
    private static final int MAX_LIMIT = 1000;

    @Autowired
    private PositionSnapshotMapper positionSnapshotMapper;

    @PostMapping("/api/v1/position/query-profitable")
    public Result<List<AdlPositionView>> queryProfitable(@RequestBody(required = false) PositionProfitableQuery request) {
        PositionProfitableQuery safeRequest = request == null ? new PositionProfitableQuery() : request;
        String symbol = normalizeSymbol(safeRequest.getSymbol());
        if (symbol == null) {
            return Result.error("symbol is required");
        }

        Integer expectedSide = parseSide(safeRequest.getSide());
        int limit = normalizeLimit(safeRequest.getLimit());
        boolean onlyProfitable = safeRequest.getOnlyProfitable() == null || safeRequest.getOnlyProfitable();
        BigDecimal minPnlRatio = parseDecimal(safeRequest.getMinPnlRatio());

        List<PositionSnapshot> snapshots = positionSnapshotMapper.selectBySymbol(symbol);
        List<AdlPositionView> candidates = new ArrayList<>();
        for (PositionSnapshot snapshot : snapshots) {
            if (snapshot == null || !snapshot.hasPosition()) {
                continue;
            }
            if (expectedSide != null && !expectedSide.equals(snapshot.getPositionSide())) {
                continue;
            }

            AdlPositionView view = toAdlView(snapshot);
            if (onlyProfitable && view.getUnrealizedPnl().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (minPnlRatio != null && view.getPnlRatio().compareTo(minPnlRatio) < 0) {
                continue;
            }
            candidates.add(view);
        }

        candidates.sort(Comparator
            .comparing(AdlPositionView::getAdlScore, Comparator.nullsFirst(Comparator.naturalOrder())).reversed()
            .thenComparing(AdlPositionView::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));

        if (candidates.size() > limit) {
            candidates = new ArrayList<>(candidates.subList(0, limit));
        }

        log.info("[PositionAdlController] Query profitable positions, symbol={}, side={}, count={}",
            symbol, safeRequest.getSide(), candidates.size());
        return Result.success(candidates);
    }

    @GetMapping("/api/v1/position/get-by-id")
    public Result<AdlPositionView> getById(@RequestParam("positionId") Long positionId) {
        if (positionId == null || positionId <= 0) {
            return Result.error("positionId is required");
        }

        PositionSnapshot snapshot = positionSnapshotMapper.selectById(positionId);
        if (snapshot == null) {
            return Result.error("position not found");
        }
        return Result.success(toAdlView(snapshot));
    }

    @PostMapping("/internal/position/notify-adl")
    public Result<Map<String, Object>> notifyAdl(@RequestBody AdlNotifyRequest request) {
        if (request == null || request.getUserId() == null || request.getPositionId() == null
            || request.getAdlExecutionId() == null || request.getAdlExecutionId().isBlank()) {
            return Result.error("invalid notify-adl request");
        }

        PositionSnapshot snapshot = positionSnapshotMapper.selectById(request.getPositionId());
        if (snapshot == null) {
            return Result.error("position not found");
        }
        if (!request.getUserId().equals(snapshot.getUserId())) {
            return Result.error("position user mismatch");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("accepted", true);
        data.put("userId", request.getUserId());
        data.put("positionId", request.getPositionId());
        data.put("adlExecutionId", request.getAdlExecutionId());
        data.put("timestamp", System.currentTimeMillis());

        log.info("[PositionAdlController] ADL notify accepted, userId={}, positionId={}, adlExecutionId={}",
            request.getUserId(), request.getPositionId(), request.getAdlExecutionId());
        return Result.success(data);
    }

    private AdlPositionView toAdlView(PositionSnapshot snapshot) {
        BigDecimal positionSize = nonNull(snapshot.getSize());
        BigDecimal entryPrice = nonNull(snapshot.getEntryPrice());
        BigDecimal unrealizedPnl = nonNull(snapshot.getUnrealizedPnl());
        BigDecimal realizedPnl = nonNull(snapshot.getRealizedPnl());
        BigDecimal markPrice = deriveMarkPrice(snapshot, entryPrice, positionSize, unrealizedPnl);
        BigDecimal positionValue = markPrice.multiply(positionSize);
        int leverage = DEFAULT_LEVERAGE.intValue();

        BigDecimal marginBalance = entryPrice.multiply(positionSize)
            .divide(DEFAULT_LEVERAGE, 8, RoundingMode.HALF_UP);
        if (marginBalance.compareTo(BigDecimal.ZERO) <= 0) {
            marginBalance = positionValue.divide(DEFAULT_LEVERAGE, 8, RoundingMode.HALF_UP);
        }
        if (marginBalance.compareTo(BigDecimal.ZERO) <= 0) {
            marginBalance = BigDecimal.ZERO;
        }

        BigDecimal maintenanceMargin = positionValue.multiply(MAINTENANCE_MARGIN_RATE)
            .setScale(8, RoundingMode.HALF_UP);
        BigDecimal pnlRatio = BigDecimal.ZERO;
        BigDecimal effectiveLeverage = BigDecimal.ZERO;
        if (marginBalance.compareTo(BigDecimal.ZERO) > 0) {
            pnlRatio = unrealizedPnl.divide(marginBalance, 8, RoundingMode.HALF_UP);
            effectiveLeverage = positionValue.divide(marginBalance, 8, RoundingMode.HALF_UP);
        }

        BigDecimal adlScore = BigDecimal.ZERO;
        if (unrealizedPnl.compareTo(BigDecimal.ZERO) > 0 && marginBalance.compareTo(BigDecimal.ZERO) > 0) {
            adlScore = pnlRatio.multiply(effectiveLeverage)
                .multiply(SCORE_FACTOR)
                .setScale(8, RoundingMode.HALF_UP);
        }

        AdlPositionView view = new AdlPositionView();
        view.setPositionId(snapshot.getId());
        view.setUserId(snapshot.getUserId());
        view.setSymbol(snapshot.getSymbol());
        view.setSide(snapshot.isLong() ? "LONG" : "SHORT");
        view.setPositionSize(positionSize);
        view.setEntryPrice(entryPrice);
        view.setMarkPrice(markPrice);
        view.setLiquidationPrice(nonNull(snapshot.getLiquidationPrice()));
        view.setMarginBalance(marginBalance);
        view.setMaintenanceMargin(maintenanceMargin);
        view.setUnrealizedPnl(unrealizedPnl);
        view.setRealizedPnl(realizedPnl);
        view.setLeverage(leverage);
        view.setStatus(positionSize.compareTo(BigDecimal.ZERO) > 0 ? "ACTIVE" : "CLOSED");
        view.setCreatedAt(snapshot.getCreatedAt());
        view.setUpdatedAt(snapshot.getUpdatedAt());
        view.setPnlRatio(pnlRatio);
        view.setEffectiveLeverage(effectiveLeverage);
        view.setAdlScore(adlScore);
        return view;
    }

    private BigDecimal deriveMarkPrice(PositionSnapshot snapshot,
                                       BigDecimal entryPrice,
                                       BigDecimal positionSize,
                                       BigDecimal unrealizedPnl) {
        if (positionSize.compareTo(BigDecimal.ZERO) <= 0) {
            return entryPrice;
        }

        BigDecimal delta = unrealizedPnl.divide(positionSize, 8, RoundingMode.HALF_UP);
        BigDecimal markPrice = snapshot.isLong() ? entryPrice.add(delta) : entryPrice.subtract(delta);
        if (markPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return entryPrice.max(BigDecimal.ONE);
        }
        return markPrice;
    }

    private Integer parseSide(String side) {
        if (side == null || side.isBlank()) {
            return null;
        }
        String normalized = side.trim().toUpperCase(Locale.ROOT);
        if ("LONG".equals(normalized)) {
            return 1;
        }
        if ("SHORT".equals(normalized)) {
            return 2;
        }
        return null;
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 100;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (Exception ignore) {
            return null;
        }
    }

    private BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    @Data
    public static class PositionProfitableQuery {
        private String symbol;
        private String side;
        private Boolean onlyProfitable;
        private String minPnlRatio;
        private String orderBy;
        private Integer limit;
    }

    @Data
    public static class AdlNotifyRequest {
        private Long userId;
        private Long positionId;
        private String adlExecutionId;
    }

    @Data
    public static class AdlPositionView {
        private Long positionId;
        private Long userId;
        private String symbol;
        private String side;
        private BigDecimal positionSize;
        private BigDecimal entryPrice;
        private BigDecimal markPrice;
        private BigDecimal liquidationPrice;
        private BigDecimal marginBalance;
        private BigDecimal maintenanceMargin;
        private BigDecimal unrealizedPnl;
        private BigDecimal realizedPnl;
        private Integer leverage;
        private String status;
        private Long createdAt;
        private Long updatedAt;
        private BigDecimal pnlRatio;
        private BigDecimal effectiveLeverage;
        private BigDecimal adlScore;
    }
}
