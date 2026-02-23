package com.exchange.common.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额精度处理工具类
 * 使用 long 存储金额，避免浮点数精度问题
 * SCALE = 100_000_000 (1亿)，支持8位小数
 * 
 * 示例：
 * - 1.23 USD = 123_000_000
 * - 0.00000001 BTC = 1
 */
public final class Money {
    
    /**
     * 精度系数：1亿 = 8位小数
     */
    public static final long SCALE = 100_000_000L;
    
    /**
     * 最小单位（1聪）
     */
    public static final long MIN_UNIT = 1L;
    
    private Money() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    /**
     * 将 double 转换为 long（内部存储格式）
     * @param value 浮点数值
     * @return long 格式金额
     */
    public static long of(double value) {
        return (long) (value * SCALE);
    }
    
    /**
     * 将 long 转换回 double（用于展示）
     * @param value long 格式金额
     * @return 浮点数值
     */
    public static double toDouble(long value) {
        return (double) value / SCALE;
    }
    
    /**
     * 将 long 转换为 BigDecimal（用于精确计算）
     * 修复：支持 ledger-core 使用 BigDecimal 进行计算
     * 
     * @param value long 格式金额
     * @return BigDecimal 格式金额（保留8位小数）
     */
    public static BigDecimal toBigDecimal(long value) {
        return BigDecimal.valueOf(value)
                .divide(BigDecimal.valueOf(SCALE), 8, RoundingMode.HALF_UP);
    }
    
    /**
     * 字符串转 long
     * @param value 字符串金额
     * @return long 格式金额
     */
    public static long parse(String value) {
        return of(Double.parseDouble(value));
    }
    
    /**
     * long 格式化为字符串（保留8位小数）
     * @param value long 格式金额
     * @return 格式化字符串
     */
    public static String format(long value) {
        return String.format("%.8f", toDouble(value));
    }
    
    /**
     * 加法
     */
    public static long add(long a, long b) {
        return a + b;
    }
    
    /**
     * 减法
     */
    public static long subtract(long a, long b) {
        return a - b;
    }
    
    /**
     * 乘法（需要除以 SCALE 保持精度）
     */
    public static long multiply(long a, long b) {
        return (a * b) / SCALE;
    }
    
    /**
     * 除法（需要乘以 SCALE 保持精度）
     */
    public static long divide(long a, long b) {
        return (a * SCALE) / b;
    }
    
    /**
     * 比较
     */
    public static int compare(long a, long b) {
        return Long.compare(a, b);
    }
    
    /**
     * 是否为零
     */
    public static boolean isZero(long value) {
        return value == 0L;
    }
    
    /**
     * 是否为正
     */
    public static boolean isPositive(long value) {
        return value > 0L;
    }
    
    /**
     * 是否为负
     */
    public static boolean isNegative(long value) {
        return value < 0L;
    }
}




