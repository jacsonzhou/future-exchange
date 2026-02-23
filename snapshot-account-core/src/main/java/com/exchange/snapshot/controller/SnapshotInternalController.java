package com.exchange.snapshot.controller;

import com.exchange.snapshot.entity.AccountSnapshot;
import com.exchange.snapshot.service.AccountSnapshotService;
import com.exchange.snapshot.service.ReplayService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * Snapshot Account Internal Controller（生产级）
 * 
 * 🔥 核心职责：
 * 1. 供风控查询账户快照
 * 2. 供API查询账户信息
 * 3. 触发Replay
 * 
 * ⚠️ 注意：本服务只负责账户快照，不包含持仓快照
 */
@Slf4j
@RestController
@RequestMapping("/internal/snapshot")
public class SnapshotInternalController {
    
    @Autowired
    private AccountSnapshotService accountSnapshotService;
    
    @Autowired
    private ReplayService replayService;
    
    /**
     * 查询账户快照（风控高频调用）
     * 
     * 查询顺序：
     * 1. Redis（毫秒级）
     * 2. MySQL（Miss时）
     */
    @GetMapping("/account/{userId}")
    public AccountSnapshot getAccountSnapshot(@PathVariable("userId") Long userId) {
        log.info("[SnapshotController] Query account, userId={}", userId);
        AccountSnapshot snapshot = accountSnapshotService.queryAccount(userId);
        if (snapshot == null) {
            // 返回空账户快照，避免前端报错
            snapshot = new AccountSnapshot();
            snapshot.setUserId(userId);
            snapshot.setCurrency("USDT");
            snapshot.setAvailable(new java.math.BigDecimal("0"));
            snapshot.setFrozen(new java.math.BigDecimal("0"));
            snapshot.setPositionMargin(new java.math.BigDecimal("0"));
            snapshot.setUnrealizedPnl(new java.math.BigDecimal("0"));
            snapshot.setRealizedPnl(new java.math.BigDecimal("0"));
            snapshot.setEquity(new java.math.BigDecimal("0"));
            snapshot.setMarginRatio(new java.math.BigDecimal("0"));
            snapshot.setLastBizSeq(0L);
            snapshot.setVersion(0);
        }
        return snapshot;
    }
    
    /**
     * 获取用户可用余额（供OMS风控使用）
     * 
     * @param userId 用户ID
     * @return 可用余额（单位：分），如果用户不存在返回null
     */
    @GetMapping("/balance/available")
    public Long getUserAvailableBalance(@RequestParam("userId") Long userId) {
        log.debug("[SnapshotController] Query available balance, userId={}", userId);
        AccountSnapshot snapshot = accountSnapshotService.queryAccount(userId);
        if (snapshot == null) {
            return null;
        }
        // 转换为内部存储单位（分）
        return snapshot.getAvailable().multiply(new java.math.BigDecimal("100000000")).longValue();
    }
    
    /**
     * 获取用户总余额（供OMS风控使用）
     * 
     * @param userId 用户ID
     * @return 总余额（单位：分），如果用户不存在返回null
     */
    @GetMapping("/balance/total")
    public Long getUserTotalBalance(@RequestParam("userId") Long userId) {
        log.debug("[SnapshotController] Query total balance, userId={}", userId);
        AccountSnapshot snapshot = accountSnapshotService.queryAccount(userId);
        if (snapshot == null) {
            return null;
        }
        // 总余额 = 可用 + 冻结 + 持仓保证金
        java.math.BigDecimal total = snapshot.getAvailable()
            .add(snapshot.getFrozen())
            .add(snapshot.getPositionMargin());
        return total.multiply(new java.math.BigDecimal("100000000")).longValue();
    }
    
    /**
     * 触发Symbol Replay
     */
    @PostMapping("/replay/symbol")
    public String replaySymbol(@RequestBody ReplaySymbolRequest request) {
        log.info("[SnapshotController] Replay symbol, symbol={}", request.getSymbol());
        return replayService.replaySymbol(request.getSymbol());
    }
    
    /**
     * 按时间区间Replay
     */
    @PostMapping("/replay/range")
    public String replayRange(@RequestBody ReplayRangeRequest request) {
        log.info("[SnapshotController] Replay range, symbol={}, startTs={}, endTs={}",
            request.getSymbol(), request.getStartTs(), request.getEndTs());
        return replayService.replayRange(
            request.getSymbol(), 
            request.getStartTs(), 
            request.getEndTs()
        );
    }
    
    /**
     * 查询Replay进度
     */
    @GetMapping("/replay/progress/{replayId}")
    public Integer getReplayProgress(@PathVariable String replayId) {
        return replayService.getReplayProgress(replayId);
    }
    
    // ==================== DTO ====================
    
    @Data
    public static class ReplaySymbolRequest {
        private String symbol;
    }
    
    @Data
    public static class ReplayRangeRequest {
        private String symbol;
        private Long startTs;
        private Long endTs;
    }
}
