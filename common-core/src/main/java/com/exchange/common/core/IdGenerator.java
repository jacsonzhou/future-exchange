package com.exchange.common.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局唯一ID生成器
 * 简化版雪花算法
 * 
 * 结构（64位）：
 * - 1位符号位（固定0）
 * - 41位时间戳（毫秒级，可用69年）
 * - 10位机器ID
 * - 12位序列号（每毫秒4096个ID）
 */
public class IdGenerator {
    
    /**
     * 起始时间戳（2024-01-01 00:00:00）
     */
    private static final long START_TIMESTAMP = 1704067200000L;
    
    /**
     * 机器ID位数
     */
    private static final long WORKER_ID_BITS = 10L;
    
    /**
     * 序列号位数
     */
    private static final long SEQUENCE_BITS = 12L;
    
    /**
     * 机器ID最大值
     */
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    
    /**
     * 序列号掩码
     */
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);
    
    /**
     * 机器ID左移位数
     */
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    
    /**
     * 时间戳左移位数
     */
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    
    private final long workerId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;
    
    private static volatile IdGenerator instance;
    
    private IdGenerator(long workerId) {
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException(
                String.format("Worker ID must be between 0 and %d", MAX_WORKER_ID));
        }
        this.workerId = workerId;
    }
    
    /**
     * 获取单例实例
     */
    public static IdGenerator getInstance(long workerId) {
        if (instance == null) {
            synchronized (IdGenerator.class) {
                if (instance == null) {
                    instance = new IdGenerator(workerId);
                }
            }
        }
        return instance;
    }
    
    /**
     * 生成下一个ID（实例方法）
     */
    public synchronized long nextId() {
        long timestamp = currentTimeMillis();
        
        if (timestamp < lastTimestamp) {
            throw new RuntimeException(
                String.format("Clock moved backwards. Refusing to generate id for %d milliseconds", 
                    lastTimestamp - timestamp));
        }
        
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // 序列号用尽，等待下一毫秒
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        
        lastTimestamp = timestamp;
        
        return ((timestamp - START_TIMESTAMP) << TIMESTAMP_SHIFT) |
               (workerId << WORKER_ID_SHIFT) |
               sequence;
    }
    
    private long waitNextMillis(long lastTimestamp) {
        long timestamp = currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = currentTimeMillis();
        }
        return timestamp;
    }
    
    private long currentTimeMillis() {
        return System.currentTimeMillis();
    }
    
    /**
     * 便捷方法：使用默认workerId=1
     */
    public static long generate() {
        return getInstance(1).nextId();
    }
}




