package com.exchange.margin.service;

import com.exchange.margin.entity.CrossMarginSnapshot;
import com.exchange.margin.entity.PositionMarginDetail;

import java.util.List;

/**
 * 保证金模式服务接口（生产级）
 * 
 * 🔥 核心职责：
 * 1. 全仓/逐仓模式切换
 * 2. 逐仓保证金追加/减少
 * 3. 仓位保证金详情查询
 * 4. 全仓账户风险快照计算
 * 5. 强平价格计算
 * 6. 提供风控所需接口
 * 
 * 对标：Binance / OKX / Bybit级别
 */
public interface MarginModeService {
    
    // ==================== 🔥 仓位保证金管理 ====================
    
    /**
     * 创建仓位保证金详情（开仓时调用）
     * 
     * @param userId 用户ID
     * @param positionId 仓位ID
     * @param symbol 交易对
     * @param side 持仓方向：1=多头, 2=空头
     * @param marginMode 保证金模式：CROSS/ISOLATED
     * @param leverage 杠杆倍数
     * @param isolatedMargin 逐仓保证金金额（仅逐仓模式）
     * @return 创建的仓位保证金详情
     */
    PositionMarginDetail createPositionMargin(Long userId, Long positionId, String symbol, 
                                               Integer side, String marginMode, 
                                               Integer leverage, Long isolatedMargin);
    
    /**
     * 查询仓位保证金详情
     * 
     * @param positionId 仓位ID
     * @return 仓位保证金详情
     */
    PositionMarginDetail getPositionMargin(Long positionId);
    
    /**
     * 查询用户所有仓位保证金详情
     * 
     * @param userId 用户ID
     * @return 仓位保证金详情列表
     */
    List<PositionMarginDetail> getUserPositionMargins(Long userId);
    
    /**
     * 查询用户的全仓仓位
     * 
     * @param userId 用户ID
     * @return 全仓仓位列表
     */
    List<PositionMarginDetail> getUserCrossPositions(Long userId);
    
    /**
     * 查询用户的逐仓仓位
     * 
     * @param userId 用户ID
     * @return 逐仓仓位列表
     */
    List<PositionMarginDetail> getUserIsolatedPositions(Long userId);
    
    /**
     * 删除仓位保证金详情（平仓后调用）
     * 
     * @param positionId 仓位ID
     */
    void deletePositionMargin(Long positionId);
    
    // ==================== 🔥 保证金模式切换 ====================
    
    /**
     * 切换保证金模式（全仓/逐仓）
     * 
     * 🔥 限制条件：
     * 1. 有持仓时切换需要满足新模式的保证金要求
     * 2. 逐仓转全仓：isolatedMargin将归还到账户余额
     * 3. 全仓转逐仓：需要账户有足够余额作为逐仓保证金
     * 
     * @param positionId 仓位ID
     * @param targetMode 目标模式：CROSS/ISOLATED
     * @param isolatedMargin 逐仓保证金金额（全仓转逐仓时需要）
     * @return 更新后的仓位保证金详情
     */
    PositionMarginDetail switchMarginMode(Long positionId, String targetMode, Long isolatedMargin);
    
    /**
     * 校验是否可以切换保证金模式
     * 
     * @param positionId 仓位ID
     * @param targetMode 目标模式
     * @return 是否可以切换
     */
    boolean canSwitchMarginMode(Long positionId, String targetMode);
    
    // ==================== 🔥 逐仓保证金调整 ====================
    
    /**
     * 追加逐仓保证金
     * 
     * @param positionId 仓位ID
     * @param amount 追加金额
     * @return 更新后的仓位保证金详情
     */
    PositionMarginDetail addIsolatedMargin(Long positionId, Long amount);
    
    /**
     * 减少逐仓保证金
     * 
     * 🔥 限制：减少后保证金必须满足维持保证金要求
     * 
     * @param positionId 仓位ID
     * @param amount 减少金额
     * @return 更新后的仓位保证金详情
     */
    PositionMarginDetail reduceIsolatedMargin(Long positionId, Long amount);
    
    /**
     * 调整杠杆倍数
     * 
     * 🔥 限制：
     * 1. 新杠杆必须在允许范围内（1-125倍）
     * 2. 降低杠杆需要额外保证金
     * 3. 提高杠杆需要满足新杠杆的保证金要求
     * 
     * @param positionId 仓位ID
     * @param newLeverage 新杠杆倍数
     * @return 更新后的仓位保证金详情
     */
    PositionMarginDetail adjustLeverage(Long positionId, Integer newLeverage);
    
    // ==================== 🔥 保证金计算 ====================
    
    /**
     * 计算仓位保证金（实时）
     * 
     * @param positionId 仓位ID
     * @param currentPrice 当前价格
     * @return 计算后的仓位保证金详情
     */
    PositionMarginDetail calculatePositionMargin(Long positionId, Long currentPrice);
    
    /**
     * 计算强平价格
     * 
     * 逐仓模式公式：
     * 多头：liquidationPrice = entryPrice * (1 - isolatedMargin / positionValue + maintenanceMarginRate)
     * 空头：liquidationPrice = entryPrice * (1 + isolatedMargin / positionValue - maintenanceMarginRate)
     * 
     * @param positionId 仓位ID
     * @return 预估强平价格
     */
    Long calculateLiquidationPrice(Long positionId);
    
    /**
     * 计算破产价格（保证金归零的价格）
     * 
     * @param positionId 仓位ID
     * @return 破产价格
     */
    Long calculateBankruptcyPrice(Long positionId);
    
    /**
     * 更新仓位保证金信息（价格变动时调用）
     * 
     * @param positionId 仓位ID
     * @param markPrice 标记价格
     */
    void updatePositionMarginByPrice(Long positionId, Long markPrice);
    
    // ==================== 🔥 全仓账户快照 ====================
    
    /**
     * 获取或创建全仓账户快照
     * 
     * @param userId 用户ID
     * @return 全仓账户快照
     */
    CrossMarginSnapshot getOrCreateCrossSnapshot(Long userId);
    
    /**
     * 查询全仓账户快照
     * 
     * @param userId 用户ID
     * @return 全仓账户快照
     */
    CrossMarginSnapshot getCrossMarginSnapshot(Long userId);
    
    /**
     * 计算并更新全仓账户快照
     * 
     * @param userId 用户ID
     * @return 更新后的全仓账户快照
     */
    CrossMarginSnapshot calculateCrossSnapshot(Long userId);
    
    /**
     * 批量计算全仓账户快照
     * 
     * @param userIds 用户ID列表
     * @return 快照列表
     */
    List<CrossMarginSnapshot> batchCalculateCrossSnapshot(List<Long> userIds);
    
    // ==================== 🔥 风控接口 ====================
    
    /**
     * 检查是否需要强平（逐仓）
     * 
     * @param positionId 仓位ID
     * @param currentPrice 当前价格
     * @return 是否需要强平
     */
    boolean checkLiquidationNeeded(Long positionId, Long currentPrice);
    
    /**
     * 检查账户是否需要强平（全仓）
     * 
     * @param userId 用户ID
     * @return 是否需要强平
     */
    boolean checkAccountLiquidationNeeded(Long userId);
    
    /**
     * 查询需要强平的仓位列表
     * 
     * @return 需要强平的仓位列表
     */
    List<PositionMarginDetail> getLiquidationCandidates();
    
    /**
     * 查询需要强平的账户列表
     * 
     * @return 需要强平的账户列表
     */
    List<CrossMarginSnapshot> getAccountLiquidationCandidates();
    
    /**
     * 获取账户可用保证金（风控下单检查用）
     * 
     * @param userId 用户ID
     * @param marginMode 保证金模式
     * @return 可用保证金
     */
    Long getAvailableMargin(Long userId, String marginMode);
    
    /**
     * 校验下单保证金是否充足
     * 
     * @param userId 用户ID
     * @param symbol 交易对
     * @param side 方向
     * @param marginMode 保证金模式
     * @param requiredMargin 所需保证金
     * @return 是否充足
     */
    boolean validateMarginSufficient(Long userId, String symbol, Integer side, 
                                     String marginMode, Long requiredMargin);
    
    // ==================== 🔥 风险查询 ====================
    
    /**
     * 查询高风险账户列表
     * 
     * @param threshold 保证金率阈值（万分比）
     * @return 高风险账户列表
     */
    List<CrossMarginSnapshot> getHighRiskAccounts(Long threshold);
    
    /**
     * 获取账户风险等级
     * 
     * @param userId 用户ID
     * @return 风险等级代码：1=SAFE, 2=WARNING, 3=DANGER, 4=LIQUIDATION
     */
    Integer getAccountRiskLevel(Long userId);
    
    /**
     * 获取账户保证金率
     * 
     * @param userId 用户ID
     * @return 保证金率（万分比）
     */
    Long getAccountMarginRatio(Long userId);
}
