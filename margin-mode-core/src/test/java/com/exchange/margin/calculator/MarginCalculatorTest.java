package com.exchange.margin.calculator;

import com.exchange.margin.entity.PositionMarginDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 保证金计算器测试
 *
 * 测试核心计算逻辑的准确性
 */
class MarginCalculatorTest {

    private MarginCalculator marginCalculator;

    @BeforeEach
    void setUp() {
        marginCalculator = new MarginCalculator();
    }

    /**
     * 测试：逐仓保证金计算
     */
    @Test
    void testCalculatePositionMargin_Isolated() {
        // 场景：逐仓模式，1 BTC @ 50000 USDT，20倍杠杆
        // 仓位价值：50000 USDT
        // 逐仓保证金：2500 USDT（50000 / 20）
        Long positionValue = 5000000000000L; // 50000 USDT (精度8位)
        Integer leverage = 20;
        Long isolatedMargin = 250000000000L; // 2500 USDT

        Long margin = marginCalculator.calculatePositionMargin(
                positionValue, leverage, "ISOLATED", isolatedMargin
        );

        // 逐仓模式应该返回isolatedMargin
        assertEquals(isolatedMargin, margin);
    }

    /**
     * 测试：全仓保证金计算
     */
    @Test
    void testCalculatePositionMargin_Cross() {
        // 场景：全仓模式，1 BTC @ 50000 USDT，10倍杠杆
        // 仓位价值：50000 USDT
        // 预期保证金：5000 USDT（50000 / 10）
        Long positionValue = 5000000000000L; // 50000 USDT
        Integer leverage = 10;

        Long margin = marginCalculator.calculatePositionMargin(
                positionValue, leverage, "CROSS", null
        );

        assertEquals(500000000000L, margin); // 5000 USDT
    }

    /**
     * 测试：维持保证金计算
     */
    @Test
    void testCalculateMaintenanceMargin() {
        // 场景：仓位价值50000 USDT，维持保证金率0.5%（50万分比）
        // 预期维持保证金：250 USDT
        Long positionValue = 5000000000000L; // 50000 USDT
        Long maintMarginRate = 50L; // 0.5%

        Long maintMargin = marginCalculator.calculateMaintenanceMargin(
                positionValue, maintMarginRate
        );

        assertEquals(25000000000L, maintMargin); // 250 USDT
    }

    /**
     * 测试：未实现盈亏计算 - 多头盈利
     */
    @Test
    void testCalculateUnrealizedPnl_LongProfit() {
        // 场景：多头，持仓1 BTC，开仓价50000，标记价52000
        // 预期盈利：(52000 - 50000) × 1 = 2000 USDT
        Integer side = 1; // 多头
        Long positionQty = 100000000L; // 1 BTC (精度8位)
        Long entryPrice = 5000000000000L; // 50000 USDT
        Long markPrice = 5200000000000L; // 52000 USDT

        Long unrealizedPnl = marginCalculator.calculateUnrealizedPnl(
                side, positionQty, entryPrice, markPrice
        );

        assertEquals(200000000000L, unrealizedPnl); // 2000 USDT
    }

    /**
     * 测试：未实现盈亏计算 - 空头盈利
     */
    @Test
    void testCalculateUnrealizedPnl_ShortProfit() {
        // 场景：空头，持仓1 BTC，开仓价52000，标记价50000
        // 预期盈利：(52000 - 50000) × 1 = 2000 USDT
        Integer side = 2; // 空头
        Long positionQty = 100000000L; // 1 BTC
        Long entryPrice = 5200000000000L; // 52000 USDT
        Long markPrice = 5000000000000L; // 50000 USDT

        Long unrealizedPnl = marginCalculator.calculateUnrealizedPnl(
                side, positionQty, entryPrice, markPrice
        );

        assertEquals(200000000000L, unrealizedPnl); // 2000 USDT
    }

    /**
     * 测试：保证金率计算
     */
    @Test
    void testCalculateMarginRatio() {
        // 场景：逐仓保证金2500 USDT，仓位价值50000 USDT
        // 预期保证金率：5%（500万分比）
        Long isolatedMargin = 250000000000L; // 2500 USDT
        Long positionValue = 5000000000000L; // 50000 USDT

        Long marginRatio = marginCalculator.calculateMarginRatio(
                isolatedMargin, positionValue
        );

        assertEquals(500L, marginRatio); // 5% = 500万分比
    }

    /**
     * 测试：强平价格计算 - 多头
     */
    @Test
    void testCalculateLiquidationPrice_Long() {
        // 场景：多头，开仓价50000，20倍杠杆（初始保证金率5%），维持保证金率0.5%
        // 公式：50000 × (1 - 0.05 + 0.005) = 50000 × 0.955 = 47750
        PositionMarginDetail detail = new PositionMarginDetail();
        detail.setSide(1); // 多头
        detail.setMarginMode("ISOLATED");
        detail.setEntryPrice(5000000000000L); // 50000 USDT
        detail.setIsolatedMargin(250000000000L); // 2500 USDT (5%)
        detail.setPositionValue(5000000000000L); // 50000 USDT
        detail.setMaintMarginRate(50L); // 0.5%

        Long liqPrice = marginCalculator.calculateLiquidationPrice(detail);

        // 预期：47750 USDT
        assertEquals(4775000000000L, liqPrice);
    }

    /**
     * 测试：强平价格计算 - 空头
     */
    @Test
    void testCalculateLiquidationPrice_Short() {
        // 场景：空头，开仓价50000，20倍杠杆（初始保证金率5%），维持保证金率0.5%
        // 公式：50000 × (1 + 0.05 - 0.005) = 50000 × 1.045 = 52250
        PositionMarginDetail detail = new PositionMarginDetail();
        detail.setSide(2); // 空头
        detail.setMarginMode("ISOLATED");
        detail.setEntryPrice(5000000000000L); // 50000 USDT
        detail.setIsolatedMargin(250000000000L); // 2500 USDT (5%)
        detail.setPositionValue(5000000000000L); // 50000 USDT
        detail.setMaintMarginRate(50L); // 0.5%

        Long liqPrice = marginCalculator.calculateLiquidationPrice(detail);

        // 预期：52250 USDT
        assertEquals(5225000000000L, liqPrice);
    }

    /**
     * 测试：破产价格计算 - 多头
     */
    @Test
    void testCalculateBankruptcyPrice_Long() {
        // 场景：多头，开仓价50000，保证金率5%
        // 公式：50000 × (1 - 0.05) = 50000 × 0.95 = 47500
        PositionMarginDetail detail = new PositionMarginDetail();
        detail.setSide(1); // 多头
        detail.setMarginMode("ISOLATED");
        detail.setEntryPrice(5000000000000L); // 50000 USDT
        detail.setIsolatedMargin(250000000000L); // 2500 USDT
        detail.setPositionValue(5000000000000L); // 50000 USDT

        Long bankruptcyPrice = marginCalculator.calculateBankruptcyPrice(detail);

        // 预期：47500 USDT
        assertEquals(4750000000000L, bankruptcyPrice);
    }

    /**
     * 测试：破产价格计算 - 空头
     */
    @Test
    void testCalculateBankruptcyPrice_Short() {
        // 场景：空头，开仓价50000，保证金率5%
        // 公式：50000 × (1 + 0.05) = 50000 × 1.05 = 52500
        PositionMarginDetail detail = new PositionMarginDetail();
        detail.setSide(2); // 空头
        detail.setMarginMode("ISOLATED");
        detail.setEntryPrice(5000000000000L); // 50000 USDT
        detail.setIsolatedMargin(250000000000L); // 2500 USDT
        detail.setPositionValue(5000000000000L); // 50000 USDT

        Long bankruptcyPrice = marginCalculator.calculateBankruptcyPrice(detail);

        // 预期：52500 USDT
        assertEquals(5250000000000L, bankruptcyPrice);
    }

    /**
     * 测试：检查是否需要强平 - 多头触发强平
     */
    @Test
    void testCheckLiquidationNeeded_LongTriggered() {
        // 场景：多头，强平价47750，当前价格47700（低于强平价）
        PositionMarginDetail detail = new PositionMarginDetail();
        detail.setSide(1); // 多头
        detail.setMarginMode("ISOLATED");
        detail.setLiquidationPrice(4775000000000L); // 47750

        Long currentPrice = 4770000000000L; // 47700

        boolean needsLiquidation = marginCalculator.checkLiquidationNeeded(
                detail, currentPrice
        );

        assertTrue(needsLiquidation); // 应该触发强平
    }

    /**
     * 测试：检查是否需要强平 - 空头触发强平
     */
    @Test
    void testCheckLiquidationNeeded_ShortTriggered() {
        // 场景：空头，强平价52250，当前价格52300（高于强平价）
        PositionMarginDetail detail = new PositionMarginDetail();
        detail.setSide(2); // 空头
        detail.setMarginMode("ISOLATED");
        detail.setLiquidationPrice(5225000000000L); // 52250

        Long currentPrice = 5230000000000L; // 52300

        boolean needsLiquidation = marginCalculator.checkLiquidationNeeded(
                detail, currentPrice
        );

        assertTrue(needsLiquidation); // 应该触发强平
    }

    /**
     * 测试：最大可取出保证金计算
     */
    @Test
    void testCalculateMaxRemovableMargin() {
        // 场景：逐仓保证金2500 USDT，维持保证金250 USDT
        // 安全保证金：250 × 1.2 = 300 USDT
        // 最大可取出：2500 - 300 = 2200 USDT
        PositionMarginDetail detail = new PositionMarginDetail();
        detail.setMarginMode("ISOLATED");
        detail.setIsolatedMargin(250000000000L); // 2500 USDT
        detail.setMaintMargin(25000000000L); // 250 USDT

        Long maxRemovable = marginCalculator.calculateMaxRemovableMargin(detail);

        assertEquals(220000000000L, maxRemovable); // 2200 USDT
    }
}
