package com.exchange.common.core;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 时间工具类
 * 统一使用毫秒时间戳
 */
public final class TimeUtils {
    
    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FORMATTER = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    
    private TimeUtils() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    /**
     * 获取当前时间戳（毫秒）
     */
    public static long now() {
        return System.currentTimeMillis();
    }
    
    /**
     * 获取当前时间戳（纳秒）
     */
    public static long nanoTime() {
        return System.nanoTime();
    }
    
    /**
     * 时间戳转字符串
     */
    public static String format(long timestamp) {
        LocalDateTime dateTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(timestamp), ZONE_ID);
        return dateTime.format(FORMATTER);
    }
    
    /**
     * 获取当前日期字符串（用于分区）
     */
    public static String today() {
        return LocalDateTime.now(ZONE_ID)
            .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }
}







