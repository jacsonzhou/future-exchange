package com.exchange.snapshot.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Account Snapshot 账户快照（生产级）
 * 
 * 🔥 核心定位（重构后）：
 * 1. 性能层：供API/风控高频查询（O(1)）
 * 2. 派生数据：从LedgerEntry聚合计算而来
 * 3. 可重建：任何时候可以从LedgerEntry重新Replay
 * 4. 非真相源：审计对账以LedgerEntry为准
 * 5. 由AccountSnapshotService独立维护 ✅ 新增
 * 
 * 对标：Binance / OKX / Bybit级别
 */
@Data
@TableName("account_snapshot")
public class AccountSnapshot {
    
    /**
     * 用户ID（主键）
     */
    @TableId
    private Long userId;
    
    /**
     * 币种（如：USDT, BTC）
     */
    private String currency;
    
    // ==================== 🔥 账户余额 ====================
    
    /**
     * 可用余额
     */
    private BigDecimal available;
    
    /**
     * 冻结余额（下单占用）
     */
    private BigDecimal frozen;
    
    /**
     * 持仓占用保证金
     */
    private BigDecimal positionMargin;
    
    // ==================== 🔥 盈亏 ====================
    
    /**
     * 未实现盈亏（浮动盈亏）
     */
    private BigDecimal unrealizedPnl;
    
    /**
     * 已实现盈亏（累计）
     */
    private BigDecimal realizedPnl;
    
    // ==================== 🔥 权益（风控核心）====================
    
    /**
     * 权益（Equity）
     * equity = available + frozen + position_margin + unrealized_pnl
     */
    private BigDecimal equity;
    
    /**
     * 保证金率
     * marginRatio = equity / maintenanceMargin
     */
    private BigDecimal marginRatio;
    
    // ==================== 🔥 同步位点（幂等/断点续传）====================
    
    /**
     * 最后同步的biz_seq
     */
    private Long lastBizSeq;
    
    /**
     * 最后同步的entry_id
     */
    private Long lastEntryId;
    
    /**
     * 最后同步的trade_id
     */
    private String lastTradeId;
    
    // ==================== 🔥 并发控制 ====================
    
    /**
     * 版本号（乐观锁）
     */
    private Integer version;
    
    // ==================== 时间戳 ====================
    
    /**
     * 创建时间
     */
    private Long createdAt;
    
    /**
     * 更新时间
     */
    private Long updatedAt;
    
    // ==================== 辅助方法 ====================
    
    /**
     * 计算权益
     */
    public void calculateEquity() {
        this.equity = (available != null ? available : BigDecimal.ZERO)
            .add(frozen != null ? frozen : BigDecimal.ZERO)
            .add(positionMargin != null ? positionMargin : BigDecimal.ZERO)
            .add(unrealizedPnl != null ? unrealizedPnl : BigDecimal.ZERO);
    }
    
    /**
     * 计算保证金率
     */
    public void calculateMarginRatio(BigDecimal maintenanceMargin) {
        if (maintenanceMargin != null && maintenanceMargin.compareTo(BigDecimal.ZERO) > 0) {
            this.marginRatio = equity.divide(maintenanceMargin, 4, BigDecimal.ROUND_DOWN);
        }
    }
    
    /**
     * 验证余额一致性
     */
    public boolean validateBalance() {
        if (available != null && available.compareTo(BigDecimal.ZERO) < 0) return false;
        if (frozen != null && frozen.compareTo(BigDecimal.ZERO) < 0) return false;
        if (positionMargin != null && positionMargin.compareTo(BigDecimal.ZERO) < 0) return false;
        
        calculateEquity();
        
        return true;
    }
    
    /**
     * 是否可以下单（余额充足）
     */
    public boolean canPlaceOrder(BigDecimal requiredMargin) {
        return available != null 
            && available.compareTo(requiredMargin) >= 0;
    }
    
    /**
     * 是否触发强平（保证金率过低）
     */
    public boolean shouldLiquidate(BigDecimal maintenanceMargin, BigDecimal minRatio) {
        if (maintenanceMargin == null || maintenanceMargin.compareTo(BigDecimal.ZERO) == 0) {
            return false;
        }
        
        calculateMarginRatio(maintenanceMargin);
        
        return marginRatio.compareTo(minRatio) < 0;
    }
}

