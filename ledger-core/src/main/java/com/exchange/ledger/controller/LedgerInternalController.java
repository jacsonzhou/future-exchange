package com.exchange.ledger.controller;

import com.exchange.ledger.entity.AccountSnapshot;
import com.exchange.ledger.service.LedgerReplayService;
import com.exchange.ledger.service.LedgerService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * Ledger Internal Controller（生产级）
 * 
 * 🔥 核心职责：
 * 1. 内部服务调用（OMS/Risk/API）
 * 2. 查询账户快照
 * 3. 冻结/解冻保证金
 * 4. Replay接口
 */
@Slf4j
@RestController
@RequestMapping("/internal/ledger")
public class LedgerInternalController {
    
    @Autowired
    private LedgerService ledgerService;
    
    @Autowired
    private LedgerReplayService replayService;
    
    /**
     * 查询账户快照（风控高频调用）
     */
    @GetMapping("/account/{userId}")
    public AccountSnapshot getAccountSnapshot(@PathVariable Long userId) {
        log.info("[LedgerController] Get account snapshot, userId={}", userId);
        return ledgerService.getAccountSnapshot(userId);
    }
    
    /**
     * 创建初始资金（用户注册时调用）
     * 
     * POST /internal/ledger/initial-funding
     */
    @PostMapping("/initial-funding")
    public InitialFundingResponse createInitialFunding(@RequestBody InitialFundingRequest request) {
        log.info("[LedgerController] Create initial funding, userId={}, accountId={}, amount={}",
            request.getUserId(), request.getAccountId(), request.getAmount());
        
        String entryId = ledgerService.createInitialFunding(
            request.getUserId(),
            request.getAccountId(),
            request.getAsset(),
            request.getAmount(),
            request.getReason()
        );
        
        InitialFundingResponse response = new InitialFundingResponse();
        response.setEntryId(entryId);
        response.setStatus("SUCCESS");
        response.setFinalBalance(request.getAmount());
        return response;
    }
    
    /**
     * 冻结保证金（下单时）
     */
    @PostMapping("/freeze")
    public void freezeMargin(@RequestBody FreezeRequest request) {
        log.info("[LedgerController] Freeze margin, userId={}, amount={}, orderId={}",
            request.getUserId(), request.getAmount(), request.getOrderId());
        
        ledgerService.freezeMargin(
            request.getUserId(),
            request.getCurrency(),
            request.getAmount(),
            request.getOrderId()
        );
    }
    
    /**
     * 解冻保证金（撤单时）
     */
    @PostMapping("/unfreeze")
    public void unfreezeMargin(@RequestBody UnfreezeRequest request) {
        log.info("[LedgerController] Unfreeze margin, userId={}, amount={}, orderId={}",
            request.getUserId(), request.getAmount(), request.getOrderId());
        
        ledgerService.unfreezeMargin(
            request.getUserId(),
            request.getCurrency(),
            request.getAmount(),
            request.getOrderId()
        );
    }
    
    /**
     * 触发全量Replay
     */
    @PostMapping("/replay/all")
    public String replayAll(@RequestBody ReplayRequest request) {
        log.info("[LedgerController] Replay all, startSeq={}, endSeq={}",
            request.getStartBizSeq(), request.getEndBizSeq());
        
        return replayService.replayAll(
            request.getStartBizSeq(),
            request.getEndBizSeq()
        );
    }
    
    /**
     * 重建单个用户Snapshot
     */
    @PostMapping("/replay/user/{userId}")
    public AccountSnapshot replayUser(@PathVariable Long userId, @RequestParam Long fromBizSeq) {
        log.info("[LedgerController] Replay user, userId={}, fromSeq={}", userId, fromBizSeq);
        return replayService.replayUser(userId, fromBizSeq);
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
    public static class InitialFundingRequest {
        private Long accountId;
        private Long userId;
        private String asset;
        private BigDecimal amount;
        private String reason;
    }
    
    @Data
    public static class InitialFundingResponse {
        private String entryId;
        private String status;
        private BigDecimal finalBalance;
    }
    
    @Data
    public static class FreezeRequest {
        private Long userId;
        private String currency;
        private BigDecimal amount;
        private Long orderId;
    }
    
    @Data
    public static class UnfreezeRequest {
        private Long userId;
        private String currency;
        private BigDecimal amount;
        private Long orderId;
    }
    
    @Data
    public static class ReplayRequest {
        private Long startBizSeq;
        private Long endBizSeq;
    }
}
