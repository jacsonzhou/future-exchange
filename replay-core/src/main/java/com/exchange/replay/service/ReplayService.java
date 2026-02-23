package com.exchange.replay.service;

/**
 * 重放服务接口
 * 
 * 核心功能：
 * 1. 从WAL日志恢复系统状态
 * 2. 灾备恢复
 * 3. 对账验证
 */
public interface ReplayService {
    
    /**
     * 重放撮合日志
     */
    void replayMatch(String walFile);
    
    /**
     * 重放账本日志
     */
    void replayLedger(String walFile);
    
    /**
     * 完整重放（撮合+账本）
     */
    void replayAll();
    
    /**
     * 重建快照
     */
    void rebuildSnapshot();
}




