package com.exchange.margin.controller;

import com.exchange.margin.entity.CrossMarginSnapshot;
import com.exchange.margin.entity.PositionMarginDetail;
import com.exchange.margin.service.MarginModeService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 保证金模式控制器（生产级）
 * 
 * 🔥 核心职责：
 * 1. 提供内部服务调用接口（风控/OMS/快照服务）
 * 2. 仓位保证金详情查询
 * 3. 全仓账户风险快照查询
 * 4. 保证金模式切换
 * 5. 逐仓保证金调整
 * 
 * 对标：Binance / OKX / Bybit级别
 */
@Slf4j
@RestController
@RequestMapping("/internal/margin")
public class MarginModeController {

    @Autowired
    private MarginModeService marginModeService;

    // ==================== 🔥 仓位保证金查询 ====================

    /**
     * 查询仓位保证金详情
     */
    @GetMapping("/position/{positionId}")
    public PositionMarginDetail getPositionMargin(@PathVariable Long positionId) {
        log.info("[MarginModeController] Get position margin, positionId={}", positionId);
        return marginModeService.getPositionMargin(positionId);
    }

    /**
     * 查询用户所有仓位保证金
     */
    @GetMapping("/user/{userId}/positions")
    public List<PositionMarginDetail> getUserPositions(@PathVariable Long userId) {
        log.info("[MarginModeController] Get user positions, userId={}", userId);
        return marginModeService.getUserPositionMargins(userId);
    }

    /**
     * 查询用户全仓仓位
     */
    @GetMapping("/user/{userId}/cross-positions")
    public List<PositionMarginDetail> getUserCrossPositions(@PathVariable Long userId) {
        log.info("[MarginModeController] Get user cross positions, userId={}", userId);
        return marginModeService.getUserCrossPositions(userId);
    }

    /**
     * 查询用户逐仓仓位
     */
    @GetMapping("/user/{userId}/isolated-positions")
    public List<PositionMarginDetail> getUserIsolatedPositions(@PathVariable Long userId) {
        log.info("[MarginModeController] Get user isolated positions, userId={}", userId);
        return marginModeService.getUserIsolatedPositions(userId);
    }

    // ==================== 🔥 仓位保证金管理 ====================

    /**
     * 创建仓位保证金详情
     */
    @PostMapping("/position/create")
    public PositionMarginDetail createPositionMargin(@RequestBody CreatePositionRequest request) {
        log.info("[MarginModeController] Create position margin, userId={}, positionId={}, symbol={}, marginMode={}",
                request.getUserId(), request.getPositionId(), request.getSymbol(), request.getMarginMode());
        
        return marginModeService.createPositionMargin(
            request.getUserId(),
            request.getPositionId(),
            request.getSymbol(),
            request.getSide(),
            request.getMarginMode(),
            request.getLeverage(),
            request.getIsolatedMargin()
        );
    }

    /**
     * 删除仓位保证金详情（平仓后）
     */
    @PostMapping("/position/{positionId}/delete")
    public void deletePositionMargin(@PathVariable Long positionId) {
        log.info("[MarginModeController] Delete position margin, positionId={}", positionId);
        marginModeService.deletePositionMargin(positionId);
    }

    /**
     * 切换保证金模式
     */
    @PostMapping("/position/{positionId}/switch-mode")
    public PositionMarginDetail switchMarginMode(@PathVariable Long positionId, 
                                                  @RequestBody SwitchModeRequest request) {
        log.info("[MarginModeController] Switch margin mode, positionId={}, targetMode={}", 
                positionId, request.getTargetMode());
        
        return marginModeService.switchMarginMode(
            positionId, 
            request.getTargetMode(),
            request.getIsolatedMargin()
        );
    }

    // ==================== 🔥 逐仓保证金调整 ====================

    /**
     * 追加逐仓保证金
     */
    @PostMapping("/position/{positionId}/add-margin")
    public PositionMarginDetail addIsolatedMargin(@PathVariable Long positionId,
                                                   @RequestBody AdjustMarginRequest request) {
        log.info("[MarginModeController] Add isolated margin, positionId={}, amount={}", 
                positionId, request.getAmount());
        
        return marginModeService.addIsolatedMargin(positionId, request.getAmount());
    }

    /**
     * 减少逐仓保证金
     */
    @PostMapping("/position/{positionId}/reduce-margin")
    public PositionMarginDetail reduceIsolatedMargin(@PathVariable Long positionId,
                                                      @RequestBody AdjustMarginRequest request) {
        log.info("[MarginModeController] Reduce isolated margin, positionId={}, amount={}", 
                positionId, request.getAmount());
        
        return marginModeService.reduceIsolatedMargin(positionId, request.getAmount());
    }

    /**
     * 调整杠杆倍数
     */
    @PostMapping("/position/{positionId}/leverage")
    public PositionMarginDetail adjustLeverage(@PathVariable Long positionId,
                                                @RequestBody AdjustLeverageRequest request) {
        log.info("[MarginModeController] Adjust leverage, positionId={}, newLeverage={}", 
                positionId, request.getNewLeverage());
        
        return marginModeService.adjustLeverage(positionId, request.getNewLeverage());
    }

    // ==================== 🔥 全仓账户快照 ====================

    /**
     * 查询全仓账户快照
     */
    @GetMapping("/user/{userId}/cross-snapshot")
    public CrossMarginSnapshot getCrossSnapshot(@PathVariable Long userId) {
        log.info("[MarginModeController] Get cross snapshot, userId={}", userId);
        return marginModeService.getCrossMarginSnapshot(userId);
    }

    /**
     * 计算并更新全仓账户快照
     */
    @PostMapping("/user/{userId}/calculate-snapshot")
    public CrossMarginSnapshot calculateCrossSnapshot(@PathVariable Long userId) {
        log.info("[MarginModeController] Calculate cross snapshot, userId={}", userId);
        return marginModeService.calculateCrossSnapshot(userId);
    }

    /**
     * 批量计算全仓账户快照
     */
    @PostMapping("/batch-calculate-snapshot")
    public List<CrossMarginSnapshot> batchCalculateSnapshot(@RequestBody BatchCalculateRequest request) {
        log.info("[MarginModeController] Batch calculate snapshot, userCount={}", 
                request.getUserIds().size());
        
        return marginModeService.batchCalculateCrossSnapshot(request.getUserIds());
    }

    // ==================== 🔥 保证金计算 ====================

    /**
     * 计算仓位保证金
     */
    @PostMapping("/position/{positionId}/calculate")
    public PositionMarginDetail calculatePositionMargin(@PathVariable Long positionId,
                                                         @RequestBody CalculateRequest request) {
        log.info("[MarginModeController] Calculate position margin, positionId={}, currentPrice={}", 
                positionId, request.getCurrentPrice());
        
        return marginModeService.calculatePositionMargin(positionId, request.getCurrentPrice());
    }

    /**
     * 计算强平价格
     */
    @GetMapping("/position/{positionId}/liquidation-price")
    public Long calculateLiquidationPrice(@PathVariable Long positionId) {
        log.info("[MarginModeController] Calculate liquidation price, positionId={}", positionId);
        return marginModeService.calculateLiquidationPrice(positionId);
    }

    /**
     * 计算破产价格
     */
    @GetMapping("/position/{positionId}/bankruptcy-price")
    public Long calculateBankruptcyPrice(@PathVariable Long positionId) {
        log.info("[MarginModeController] Calculate bankruptcy price, positionId={}", positionId);
        return marginModeService.calculateBankruptcyPrice(positionId);
    }

    /**
     * 更新仓位保证金信息（价格变动时调用）
     */
    @PostMapping("/position/{positionId}/update-price")
    public void updatePositionMarginByPrice(@PathVariable Long positionId,
                                             @RequestBody UpdatePriceRequest request) {
        log.info("[MarginModeController] Update position margin by price, positionId={}, markPrice={}", 
                positionId, request.getMarkPrice());
        
        marginModeService.updatePositionMarginByPrice(positionId, request.getMarkPrice());
    }

    // ==================== 🔥 风控接口 ====================

    /**
     * 检查仓位是否需要强平
     */
    @PostMapping("/position/{positionId}/check-liquidation")
    public boolean checkPositionLiquidation(@PathVariable Long positionId,
                                            @RequestBody CheckLiquidationRequest request) {
        log.info("[MarginModeController] Check position liquidation, positionId={}, currentPrice={}", 
                positionId, request.getCurrentPrice());
        
        return marginModeService.checkLiquidationNeeded(positionId, request.getCurrentPrice());
    }

    /**
     * 检查账户是否需要强平
     */
    @GetMapping("/user/{userId}/check-liquidation")
    public boolean checkAccountLiquidation(@PathVariable Long userId) {
        log.info("[MarginModeController] Check account liquidation, userId={}", userId);
        return marginModeService.checkAccountLiquidationNeeded(userId);
    }

    /**
     * 查询需要强平的仓位列表
     */
    @GetMapping("/liquidation-candidates/positions")
    public List<PositionMarginDetail> getLiquidationCandidates() {
        log.info("[MarginModeController] Get liquidation candidates (positions)");
        return marginModeService.getLiquidationCandidates();
    }

    /**
     * 查询需要强平的账户列表
     */
    @GetMapping("/liquidation-candidates/accounts")
    public List<CrossMarginSnapshot> getAccountLiquidationCandidates() {
        log.info("[MarginModeController] Get liquidation candidates (accounts)");
        return marginModeService.getAccountLiquidationCandidates();
    }

    /**
     * 获取账户可用保证金（风控调用）
     */
    @GetMapping("/user/{userId}/available-margin")
    public Long getAvailableMargin(@PathVariable Long userId,
                                    @RequestParam String marginMode) {
        log.info("[MarginModeController] Get available margin, userId={}, marginMode={}", 
                userId, marginMode);
        
        return marginModeService.getAvailableMargin(userId, marginMode);
    }

    /**
     * 校验下单保证金是否充足
     */
    @PostMapping("/validate-margin")
    public boolean validateMarginSufficient(@RequestBody ValidateMarginRequest request) {
        log.info("[MarginModeController] Validate margin sufficient, userId={}, symbol={}, requiredMargin={}", 
                request.getUserId(), request.getSymbol(), request.getRequiredMargin());
        
        return marginModeService.validateMarginSufficient(
            request.getUserId(),
            request.getSymbol(),
            request.getSide(),
            request.getMarginMode(),
            request.getRequiredMargin()
        );
    }

    // ==================== 🔥 风险查询 ====================

    /**
     * 查询高风险账户
     */
    @GetMapping("/high-risk-accounts")
    public List<CrossMarginSnapshot> getHighRiskAccounts(@RequestParam Long threshold) {
        log.info("[MarginModeController] Get high risk accounts, threshold={}", threshold);
        return marginModeService.getHighRiskAccounts(threshold);
    }

    /**
     * 获取账户风险等级
     */
    @GetMapping("/user/{userId}/risk-level")
    public Integer getAccountRiskLevel(@PathVariable Long userId) {
        log.info("[MarginModeController] Get account risk level, userId={}", userId);
        return marginModeService.getAccountRiskLevel(userId);
    }

    /**
     * 获取账户保证金率
     */
    @GetMapping("/user/{userId}/margin-ratio")
    public Long getAccountMarginRatio(@PathVariable Long userId) {
        log.info("[MarginModeController] Get account margin ratio, userId={}", userId);
        return marginModeService.getAccountMarginRatio(userId);
    }

    // ==================== DTO ====================

    @Data
    public static class CreatePositionRequest {
        private Long userId;
        private Long positionId;
        private String symbol;
        private Integer side;  // 1=LONG, 2=SHORT
        private String marginMode;  // CROSS/ISOLATED
        private Integer leverage;
        private Long isolatedMargin;
    }

    @Data
    public static class SwitchModeRequest {
        private String targetMode;  // CROSS/ISOLATED
        private Long isolatedMargin;  // 全仓转逐仓时需要
    }

    @Data
    public static class AdjustMarginRequest {
        private Long amount;
    }

    @Data
    public static class AdjustLeverageRequest {
        private Integer newLeverage;
    }

    @Data
    public static class CalculateRequest {
        private Long currentPrice;
    }

    @Data
    public static class UpdatePriceRequest {
        private Long markPrice;
    }

    @Data
    public static class CheckLiquidationRequest {
        private Long currentPrice;
    }

    @Data
    public static class BatchCalculateRequest {
        private List<Long> userIds;
    }

    @Data
    public static class ValidateMarginRequest {
        private Long userId;
        private String symbol;
        private Integer side;
        private String marginMode;
        private Long requiredMargin;
    }
}
