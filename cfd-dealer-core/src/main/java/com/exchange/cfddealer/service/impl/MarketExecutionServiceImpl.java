package com.exchange.cfddealer.service.impl;

import com.exchange.cfddealer.config.CfdDealerProperties;
import com.exchange.cfddealer.dto.CfdOrderCommand;
import com.exchange.cfddealer.dto.MarketExecutionResult;
import com.exchange.cfddealer.dto.ReferenceBookSnapshot;
import com.exchange.cfddealer.service.MarketExecutionService;
import com.exchange.cfddealer.service.ReferencePricingService;
import com.exchange.common.core.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketExecutionServiceImpl implements MarketExecutionService {

    private static final BigDecimal BPS = new BigDecimal("10000");

    private final ReferencePricingService referencePricingService;
    private final CfdDealerProperties properties;

    @Override
    public MarketExecutionResult executeMarket(CfdOrderCommand command) {
        ExecutionCalculation calculation = calculate(command, false);
        if (calculation.remaining.compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException("insufficient reference depth");
        }
        return buildResult(command, calculation);
    }

    @Override
    public MarketExecutionResult executeLimit(CfdOrderCommand command) {
        ExecutionCalculation calculation = calculate(command, true);
        if (!calculation.crossed || calculation.remaining.compareTo(BigDecimal.ZERO) > 0) {
            return null;
        }
        return buildResult(command, calculation);
    }

    private ExecutionCalculation calculate(CfdOrderCommand command, boolean respectLimitPrice) {
        String symbol = normalizeSymbol(command.getSymbol());
        String side = normalizeSide(command.getSide());
        BigDecimal quantity = parsePositive(command.getQuantity(), "quantity");

        ReferenceBookSnapshot snapshot = referencePricingService.getReferenceBook(symbol);
        if (snapshot == null) {
            throw new IllegalStateException("reference book missing");
        }

        long stalenessMs = resolveStalenessMs(snapshot);
        snapshot.setStalenessMs(stalenessMs);
        if (properties.getReferenceMaxStaleMs() > 0 && stalenessMs > properties.getReferenceMaxStaleMs()) {
            throw new IllegalStateException("reference book stale: " + stalenessMs + "ms");
        }
        ensureDepth(snapshot, properties.getReferenceMinDepthLevels());

        BigDecimal bestBid = parsePositive(snapshot.getBestBid(), "bestBid");
        BigDecimal bestAsk = parsePositive(snapshot.getBestAsk(), "bestAsk");
        List<ReferenceBookSnapshot.PriceLevel> levels = "BUY".equals(side) ? snapshot.getAsksTopN() : snapshot.getBidsTopN();
        if (levels == null || levels.isEmpty()) {
            throw new IllegalStateException("reference depth empty");
        }

        BigDecimal limitPrice = null;
        if (respectLimitPrice) {
            limitPrice = parsePositive(command.getPrice(), "price");
        }

        boolean crossed = true;
        if (respectLimitPrice) {
            crossed = isCrossed(side, limitPrice, bestBid, bestAsk);
            if (!crossed) {
                return ExecutionCalculation.builder()
                    .symbol(symbol)
                    .side(side)
                    .quantity(quantity)
                    .bestBid(bestBid)
                    .bestAsk(bestAsk)
                    .remaining(quantity)
                    .vwapPrice(BigDecimal.ZERO)
                    .notional(BigDecimal.ZERO)
                    .crossed(false)
                    .snapshot(snapshot)
                    .build();
            }
        }

        BigDecimal remaining = quantity;
        BigDecimal notional = BigDecimal.ZERO;
        for (ReferenceBookSnapshot.PriceLevel level : levels) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }

            BigDecimal levelPrice = parsePositive(level.getPrice(), "level.price");
            BigDecimal levelQty = parsePositive(level.getQuantity(), "level.quantity");

            if (respectLimitPrice && !isAcceptableLevel(side, levelPrice, limitPrice)) {
                break;
            }

            BigDecimal takeQty = remaining.min(levelQty);
            notional = notional.add(takeQty.multiply(levelPrice));
            remaining = remaining.subtract(takeQty);
        }

        BigDecimal filled = quantity.subtract(remaining);
        BigDecimal vwapPrice = filled.compareTo(BigDecimal.ZERO) > 0
            ? notional.divide(filled, 8, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        return ExecutionCalculation.builder()
            .symbol(symbol)
            .side(side)
            .quantity(quantity)
            .bestBid(bestBid)
            .bestAsk(bestAsk)
            .remaining(remaining)
            .vwapPrice(vwapPrice)
            .notional(notional)
            .crossed(crossed)
            .snapshot(snapshot)
            .build();
    }

    private MarketExecutionResult buildResult(CfdOrderCommand command, ExecutionCalculation calculation) {
        BigDecimal filledQuantity = calculation.quantity.subtract(calculation.remaining);
        if (filledQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("filled quantity is zero");
        }

        BigDecimal executionPrice = calculation.notional.divide(filledQuantity, 8, RoundingMode.HALF_UP);
        int slippageBps = calculateSlippageBps(calculation.side, executionPrice, calculation.bestBid, calculation.bestAsk);

        long now = System.currentTimeMillis();
        long matchSequence = IdGenerator.generate();

        String executionMode = command.getExecutionMode();
        if (executionMode == null || executionMode.isBlank()) {
            executionMode = "CFD_DEALER";
        }

        String liquiditySource = command.getLiquiditySource();
        if (liquiditySource == null || liquiditySource.isBlank()) {
            liquiditySource = properties.getLiquiditySource();
        }

        return MarketExecutionResult.builder()
            .orderId(command.getOrderId())
            .userId(command.getUserId())
            .symbol(calculation.symbol)
            .side(calculation.side)
            .filledQuantity(filledQuantity)
            .vwapPrice(executionPrice)
            .bestBid(calculation.bestBid)
            .bestAsk(calculation.bestAsk)
            .slippageBps(slippageBps)
            .referenceTopic(firstNonBlank(command.getReferenceTopic(), calculation.snapshot.getTopic()))
            .referenceOffset(command.getReferenceOffset() != null ? command.getReferenceOffset() : calculation.snapshot.getOffset())
            .referenceEventTime(command.getReferenceEventTime() != null ? command.getReferenceEventTime() : calculation.snapshot.getEventTime())
            .executionMode(executionMode.trim().toUpperCase(Locale.ROOT))
            .liquiditySource(liquiditySource)
            .matchSequence(matchSequence)
            .tradeId(calculation.symbol + "-" + now + "-" + matchSequence)
            .tradeTime(now)
            .build();
    }

    private boolean isCrossed(String side, BigDecimal limitPrice, BigDecimal bestBid, BigDecimal bestAsk) {
        if ("BUY".equals(side)) {
            return bestAsk.compareTo(limitPrice) <= 0;
        }
        return bestBid.compareTo(limitPrice) >= 0;
    }

    private boolean isAcceptableLevel(String side, BigDecimal levelPrice, BigDecimal limitPrice) {
        if ("BUY".equals(side)) {
            return levelPrice.compareTo(limitPrice) <= 0;
        }
        return levelPrice.compareTo(limitPrice) >= 0;
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is blank");
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSide(String side) {
        if (side == null || side.isBlank()) {
            throw new IllegalArgumentException("side is blank");
        }
        String normalized = side.trim().toUpperCase(Locale.ROOT);
        if (!"BUY".equals(normalized) && !"SELL".equals(normalized)) {
            throw new IllegalArgumentException("unsupported side: " + side);
        }
        return normalized;
    }

    private BigDecimal parsePositive(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(field + " is blank");
        }
        BigDecimal parsed = new BigDecimal(raw.trim());
        if (parsed.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(field + " must be > 0");
        }
        return parsed;
    }

    private int calculateSlippageBps(String side, BigDecimal executionPrice, BigDecimal bestBid, BigDecimal bestAsk) {
        BigDecimal benchmark = "BUY".equals(side) ? bestAsk : bestBid;
        if (benchmark.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }

        BigDecimal ratio;
        if ("BUY".equals(side)) {
            ratio = executionPrice.subtract(benchmark).divide(benchmark, 8, RoundingMode.HALF_UP);
        } else {
            ratio = benchmark.subtract(executionPrice).divide(benchmark, 8, RoundingMode.HALF_UP);
        }

        BigDecimal bps = ratio.multiply(BPS).setScale(0, RoundingMode.HALF_UP);
        if (bps.compareTo(BigDecimal.ZERO) < 0) {
            return 0;
        }
        return bps.intValue();
    }

    private long resolveStalenessMs(ReferenceBookSnapshot snapshot) {
        long now = System.currentTimeMillis();
        if (snapshot.getEventTime() != null && snapshot.getEventTime() > 0) {
            return Math.max(0L, now - snapshot.getEventTime());
        }
        if (snapshot.getStalenessMs() != null) {
            return Math.max(0L, snapshot.getStalenessMs());
        }
        return Long.MAX_VALUE;
    }

    private void ensureDepth(ReferenceBookSnapshot snapshot, int minDepthLevels) {
        int requiredDepth = Math.max(1, minDepthLevels);
        int bidsDepth = snapshot.getBidsTopN() == null ? 0 : snapshot.getBidsTopN().size();
        int asksDepth = snapshot.getAsksTopN() == null ? 0 : snapshot.getAsksTopN().size();
        if (bidsDepth < requiredDepth || asksDepth < requiredDepth) {
            throw new IllegalStateException("reference depth insufficient: bids=" + bidsDepth
                + ", asks=" + asksDepth + ", required=" + requiredDepth);
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    @lombok.Builder
    private static class ExecutionCalculation {
        private String symbol;
        private String side;
        private BigDecimal quantity;
        private BigDecimal bestBid;
        private BigDecimal bestAsk;
        private BigDecimal remaining;
        private BigDecimal vwapPrice;
        private BigDecimal notional;
        private boolean crossed;
        private ReferenceBookSnapshot snapshot;
    }
}
