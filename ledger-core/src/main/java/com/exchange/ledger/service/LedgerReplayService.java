package com.exchange.ledger.service;

import com.exchange.ledger.entity.AccountSnapshot;

/**
 * Ledger Replay Service（生产级）
 * 
 * 🔥 核心职责：
 * 1. 从LedgerEntry重建AccountSnapshot
 * 2. 灾备恢复
 * 3. 数据一致性校验
 * 
 * 对标：Binance / OKX / Bybit级别
 */
public interface LedgerReplayService {
    
    /**
     * 全量Replay（重建所有AccountSnapshot）
     * 
     * 🔥 用途：
     * 1. 灾备恢复
     * 2. 数据迁移
     * 3. 一致性校验
     * 
     * @param startBizSeq 起始biz_seq（0表示从头开始）
     * @param endBizSeq 结束biz_seq（-1表示到最新）
     * @return Replay任务ID
     */
    String replayAll(Long startBizSeq, Long endBizSeq);
    
    /**
     * 增量Replay（从某个biz_seq开始）
     * 
     * @param userId 用户ID
     * @param fromBizSeq 起始biz_seq
     * @return 重建后的AccountSnapshot
     */
    AccountSnapshot replayUser(Long userId, Long fromBizSeq);
    
    /**
     * 查询Replay进度
     * 
     * @param replayId Replay任务ID
     * @return 进度百分比
     */
    Integer getReplayProgress(String replayId);
}

