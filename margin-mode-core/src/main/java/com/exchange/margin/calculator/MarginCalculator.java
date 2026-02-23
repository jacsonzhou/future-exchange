package com.exchange.margin.calculator;

import com.exchange.margin.entity.PositionMarginDetail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 保证金计算器（生产级）
 *
 * 核心功能：
 * 1. 计算仓位保证金
 * 2. 计算强平价格
 * 3. 计算破产价格
 * 4. 计算保证金率
 * 5. 计算未实现盈亏
 *
 * 精度说明：
 * - 所有金额使用 long 存储，精度8位小数（1 USDT = 10^8）
 * - 计算时先乘后除，避免精度损失
 */
@Slf4j
@Component
public class MarginCalculator {

    /**
     * 精度常量（8位小数）
     */
    public static final long PRECISION = 100000000L; // 10^8

    /**
     * 万分比常量（10000 = 100%）
     */
    public static final long RATIO_BASE = 10000L;

    /**
     * 默认维持保证金率（0.5% = 50万分比）
     */
    public static final long DEFAULT_MAINTENANCE_MARGIN_RATE = 50L;

    /**
     * 计算仓位保证金
     *
     * @param positionValue 仓位价值（持仓数量 × 标记价格）
     * @param leverage 杠杆倍数
     * @param marginMode 保证金模式：CROSS/ISOLATED
     * @param isolatedMargin 逐仓保证金（逐仓模式时使用）
     * @return 仓位保证金
     */
    public long calculatePositionMargin(Long positionValue, Integer leverage,
                                        String marginMode, Long isolatedMargin) {
        if (positionValue == null || positionValue == 0) {
            return 0L;
        }

        // 逐仓模式：保证金固定为逐仓保证金
        if ("ISOLATED".equalsIgnoreCase(marginMode)) {
            return isolatedMargin != null ? isolatedMargin : 0L;
        }

        // 全仓模式：保证金 = 仓位价值 / 杠杆
        if (leverage == null || leverage == 0) {
            leverage = 1; // 默认1倍杠杆
        }
        return positionValue / leverage;
    }

    /**
     * 计算维持保证金
     *
     * @param positionValue 仓位价值
     * @param maintMarginRate 维持保证金率（万分比）
     * @return 维持保证金
     */
    public long calculateMaintenanceMargin(Long positionValue, Long maintMarginRate) {
        if (positionValue == null || positionValue == 0) {
            return 0L;
        }
        if (maintMarginRate == null || maintMarginRate == 0) {
            maintMarginRate = DEFAULT_MAINTENANCE_MARGIN_RATE;
        }
        // 维持保证金 = 仓位价值 × 维持保证金率 / 10000
        return (positionValue * maintMarginRate) / RATIO_BASE;
    }

    /**
     * 计算未实现盈亏
     *
     * @param side 持仓方向：1=多头, 2=空头
     * @param positionQty 持仓数量
     * @param entryPrice 开仓均价
     * @param markPrice 标记价格
     * @return 未实现盈亏（正数=盈利，负数=亏损）
     */
    public long calculateUnrealizedPnl(Integer side, Long positionQty,
                                       Long entryPrice, Long markPrice) {
        if (positionQty == null || positionQty == 0 ||
            entryPrice == null || entryPrice == 0 ||
            markPrice == null || markPrice == 0) {
            return 0L;
        }

        // 多头：(标记价格 - 开仓价格) × 数量 / PRECISION
        if (side != null && side == 1) {
            return ((markPrice - entryPrice) * positionQty) / PRECISION;
        }
        // 空头：(开仓价格 - 标记价格) × 数量 / PRECISION
        else if (side != null && side == 2) {
            return ((entryPrice - markPrice) * positionQty) / PRECISION;
        }

        return 0L;
    }

    /**
     * 计算仓位价值
     *
     * @param positionQty 持仓数量
     * @param markPrice 标记价格
     * @return 仓位价值
     */
    public long calculatePositionValue(Long positionQty, Long markPrice) {
        if (positionQty == null || positionQty == 0 ||
            markPrice == null || markPrice == 0) {
            return 0L;
        }
        return (positionQty * markPrice) / PRECISION;
    }

    /**
     * 计算保证金率（逐仓模式）
     *
     * @param isolatedMargin 逐仓保证金
     * @param positionValue 仓位价值
     * @return 保证金率（万分比）
     */
    public long calculateMarginRatio(Long isolatedMargin, Long positionValue) {
        if (positionValue == null || positionValue == 0) {
            return RATIO_BASE; // 没有仓位，保证金率100%
        }
        if (isolatedMargin == null || isolatedMargin == 0) {
            return 0L;
        }
        // 保证金率 = (保证金 / 仓位价值) × 10000
        return (isolatedMargin * RATIO_BASE) / positionValue;
    }

    /**
     * 计算强平价格（逐仓模式）
     *
     * 公式：
     * 多头强平价 = 开仓均价 × (1 - 逐仓保证金/仓位价值 + 维持保证金率)
     * 空头强平价 = 开仓均价 × (1 + 逐仓保证金/仓位价值 - 维持保证金率)
     *
     * @param detail 仓位保证金详情
     * @return 强平价格
     */
    public long calculateLiquidationPrice(PositionMarginDetail detail) {
        if (detail == null || detail.getEntryPrice() == null || detail.getEntryPrice() == 0) {
            return 0L;
        }

        Long entryPrice = detail.getEntryPrice();
        Integer side = detail.getSide();
        Long positionValue = detail.getPositionValue();
        Long maintMarginRate = detail.getMaintMarginRate() != null ?
                detail.getMaintMarginRate() : DEFAULT_MAINTENANCE_MARGIN_RATE;

        // 逐仓模式
        if (detail.isIsolated()) {
            Long isolatedMargin = detail.getIsolatedMargin();
            if (isolatedMargin == null || isolatedMargin == 0 ||
                positionValue == null || positionValue == 0) {
                return 0L;
            }

            // 保证金比例 = isolatedMargin / positionValue
            long marginRatioInBasis = (isolatedMargin * RATIO_BASE) / positionValue;

            // 多头强平价
            if (side != null && side == 1) {
                // liquidationPrice = entryPrice × (1 - marginRatio + maintMarginRate/10000)
                long factor = RATIO_BASE - marginRatioInBasis + maintMarginRate;
                return (entryPrice * factor) / RATIO_BASE;
            }
            // 空头强平价
            else if (side != null && side == 2) {
                // liquidationPrice = entryPrice × (1 + marginRatio - maintMarginRate/10000)
                long factor = RATIO_BASE + marginRatioInBasis - maintMarginRate;
                return (entryPrice * factor) / RATIO_BASE;
            }
        }
        // 全仓模式：强平价依赖账户整体保证金率，这里返回0表示需要在账户级别计算
        else {
            return 0L;
        }

        return 0L;
    }

    /**
     * 计算破产价格（保证金归零的价格）
     *
     * 公式：
     * 多头破产价 = 开仓均价 × (1 - 逐仓保证金/仓位价值)
     * 空头破产价 = 开仓均价 × (1 + 逐仓保证金/仓位价值)
     *
     * @param detail 仓位保证金详情
     * @return 破产价格
     */
    public long calculateBankruptcyPrice(PositionMarginDetail detail) {
        if (detail == null || detail.getEntryPrice() == null || detail.getEntryPrice() == 0) {
            return 0L;
        }

        Long entryPrice = detail.getEntryPrice();
        Integer side = detail.getSide();
        Long positionValue = detail.getPositionValue();

        // 逐仓模式
        if (detail.isIsolated()) {
            Long isolatedMargin = detail.getIsolatedMargin();
            if (isolatedMargin == null || isolatedMargin == 0 ||
                positionValue == null || positionValue == 0) {
                return entryPrice; // 没有保证金，破产价=开仓价
            }

            // 保证金比例 = isolatedMargin / positionValue
            long marginRatioInBasis = (isolatedMargin * RATIO_BASE) / positionValue;

            // 多头破产价
            if (side != null && side == 1) {
                // bankruptcyPrice = entryPrice × (1 - marginRatio)
                long factor = RATIO_BASE - marginRatioInBasis;
                return (entryPrice * factor) / RATIO_BASE;
            }
            // 空头破产价
            else if (side != null && side == 2) {
                // bankruptcyPrice = entryPrice × (1 + marginRatio)
                long factor = RATIO_BASE + marginRatioInBasis;
                return (entryPrice * factor) / RATIO_BASE;
            }
        }
        // 全仓模式：破产价依赖账户整体情况
        else {
            return 0L;
        }

        return entryPrice;
    }

    /**
     * 计算调整杠杆后的强平价
     *
     * @param entryPrice 开仓均价
     * @param side 持仓方向
     * @param newLeverage 新杠杆倍数
     * @param maintMarginRate 维持保证金率
     * @return 新的强平价格
     */
    public long calculateLiquidationPriceByLeverage(Long entryPrice, Integer side,
                                                     Integer newLeverage, Long maintMarginRate) {
        if (entryPrice == null || entryPrice == 0 || newLeverage == null || newLeverage == 0) {
            return 0L;
        }

        if (maintMarginRate == null || maintMarginRate == 0) {
            maintMarginRate = DEFAULT_MAINTENANCE_MARGIN_RATE;
        }

        // 初始保证金率 = 1 / 杠杆
        long initialMarginRateInBasis = RATIO_BASE / newLeverage;

        // 多头强平价
        if (side != null && side == 1) {
            // liquidationPrice = entryPrice × (1 - 1/leverage + maintMarginRate/10000)
            long factor = RATIO_BASE - initialMarginRateInBasis + maintMarginRate;
            return (entryPrice * factor) / RATIO_BASE;
        }
        // 空头强平价
        else if (side != null && side == 2) {
            // liquidationPrice = entryPrice × (1 + 1/leverage - maintMarginRate/10000)
            long factor = RATIO_BASE + initialMarginRateInBasis - maintMarginRate;
            return (entryPrice * factor) / RATIO_BASE;
        }

        return 0L;
    }

    /**
     * 检查是否需要强平（逐仓）
     *
     * @param detail 仓位保证金详情
     * @param currentPrice 当前价格
     * @return 是否需要强平
     */
    public boolean checkLiquidationNeeded(PositionMarginDetail detail, Long currentPrice) {
        if (detail == null || !detail.isIsolated() ||
            currentPrice == null || currentPrice == 0) {
            return false;
        }

        Long liquidationPrice = detail.getLiquidationPrice();
        if (liquidationPrice == null || liquidationPrice == 0) {
            liquidationPrice = calculateLiquidationPrice(detail);
        }

        Integer side = detail.getSide();

        // 多头：当前价格 <= 强平价
        if (side != null && side == 1) {
            return currentPrice <= liquidationPrice;
        }
        // 空头：当前价格 >= 强平价
        else if (side != null && side == 2) {
            return currentPrice >= liquidationPrice;
        }

        return false;
    }

    /**
     * 计算最大可取出的逐仓保证金
     *
     * @param detail 仓位保证金详情
     * @return 最大可取出金额
     */
    public long calculateMaxRemovableMargin(PositionMarginDetail detail) {
        if (detail == null || !detail.isIsolated()) {
            return 0L;
        }

        Long isolatedMargin = detail.getIsolatedMargin();
        Long maintMargin = detail.getMaintMargin();

        if (isolatedMargin == null || maintMargin == null) {
            return 0L;
        }

        // 最大可取出 = 逐仓保证金 - 维持保证金 × 1.2（安全系数）
        long safeMargin = (maintMargin * 12) / 10;
        long maxRemovable = isolatedMargin - safeMargin;

        return Math.max(0, maxRemovable);
    }
}
