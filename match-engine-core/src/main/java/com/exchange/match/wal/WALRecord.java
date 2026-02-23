package com.exchange.match.wal;

import com.exchange.match.event.OrderCommand;
import com.exchange.match.model.Trade;
import lombok.Data;

import java.util.List;

/**
 * WAL 记录（批量刷盘单元）
 *
 * ============================================
 * Phase 3.1: Async WAL Optimization
 * 用于异步批量刷盘
 * ============================================
 *
 * 设计要点：
 * 1. 包含完整的撮合上下文（订单命令 + 成交事件）
 * 2. 批量化：多个 WALRecord 一起刷盘
 * 3. 时间戳：用于审计和恢复
 */
@Data
public class WALRecord {

    /**
     * 订单命令（输入）
     */
    private final OrderCommand command;

    /**
     * 成交事件列表（输出）
     */
    private final List<Trade> trades;

    /**
     * 记录时间戳（纳秒）
     */
    private final long timestampNanos;

    /**
     * 撮合序列号
     */
    private final long matchSequence;

    /**
     * 构造函数（简化版，匹配 MatchWAL 调用）
     *
     * @param command       订单命令
     * @param trades        成交事件列表
     * @param matchSequence 撮合序列号
     */
    public <T> WALRecord(OrderCommand command, List<T> trades, long matchSequence) {
        this.command = command;
        this.trades = (List<Trade>) trades; // 泛型转换
        this.matchSequence = matchSequence;
        this.timestampNanos = System.nanoTime();
    }

    /**
     * 获取记录大小（估算）
     * 用于监控和统计
     */
    public int estimateSize() {
        int size = 200; // 基础大小
        if (trades != null) {
            size += trades.size() * 150; // 每个成交约 150 字节
        }
        return size;
    }
}
