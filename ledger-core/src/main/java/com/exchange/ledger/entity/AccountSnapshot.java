package com.exchange.ledger.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Account Snapshot 账户快照（生产级）
 * 
 * 🔥 核心定位：
 * 1. 性能层：供API/风控高频查询（O(1)）
 * 2. 派生数据：从LedgerEntry聚合计算而来
 * 3. 可重建：任何时候可以从LedgerEntry重新Replay
 * 4. 非真相源：审计对账以LedgerEntry为准
 * 
 * 对标：Binance / OKX / Bybit级别
 */
@Data
@TableName("account_snapshot")
public class AccountSnapshot {
    
    /**
     * 用户ID（主键）
     * 分库键：user_id % 128
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
     * 可用于：下单、提现
     */
    private BigDecimal available;
    
    /**
     * 冻结余额（下单占用）
     * 订单未成交时，保证金从available转入frozen
     */
    private BigDecimal frozen;
    
    /**
     * 持仓占用保证金
     * 持仓时，保证金从available转入position_margin
     */
    private BigDecimal positionMargin;
    
    // ==================== 🔥 盈亏 ====================
    
    /**
     * 未实现盈亏（浮动盈亏）
     * 
     * 计算：(当前价格 - 开仓价格) * 持仓量
     * 
     * 🔥 注意：
     * - 浮盈浮亏不入LedgerEntry（只是估值）
     * - 只在平仓时，realizedPnl才入Ledger
     */
    private BigDecimal unrealizedPnl;
    
    /**
     * 已实现盈亏（累计）
     * 平仓时，PnL入Ledger，累加到这里
     */
    private BigDecimal realizedPnl;
    
    // ==================== 🔥 权益（风控核心）====================
    
    /**
     * 权益（Equity）
     * 
     * 🔥 风控核心指标！
     * 
     * 计算公式：
     * equity = available + frozen + position_margin + unrealized_pnl
     * 
     * 用途：
     * 1. 保证金率计算：marginRatio = equity / maintenanceMargin
     * 2. 强平判断：marginRatio < 1.0 触发强平
     * 3. 账户总资产
     */
    private BigDecimal equity;
    
    // ==================== 🔥 同步位点（幂等/断点续传）====================
    
    /**
     * 最后同步的biz_seq
     * 
     * 用途：
     * 1. 增量同步：SELECT * FROM ledger_entry WHERE biz_seq > last_ledger_seq
     * 2. 幂等保证：避免重复应用同一条分录
     * 3. 断点续传：崩溃重启后从last_ledger_seq继续
     */
    private Long lastLedgerSeq;
    
    /**
     * 最后同步的entry_id
     * 辅助字段，用于调试追踪
     */
    private Long lastLedgerEntryId;
    
    // ==================== 🔥 并发控制 ====================
    
    /**
     * 版本号（乐观锁）
     * 
     * 用途：
     * UPDATE account_snapshot 
     * SET available = ?, version = version + 1
     * WHERE user_id = ? AND version = ?
     * 
     * 防止并发更新导致余额错误
     */
    private Integer version;
    
    // ==================== 🔥 数据完整性 ====================
    
    /**
     * 校验哈希
     * 
     * 计算：MD5(available + frozen + position_margin + unrealized_pnl)
     * 
     * 用途：
     * 1. 快速检测数据篡改
     * 2. 对账时验证数据一致性
     */
    private String checksum;
    
    // ==================== 时间戳 ====================
    
    /**
     * 更新时间（毫秒）
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
     * 计算校验哈希
     */
    public String calculateChecksum() {
        String data = String.format("%s|%s|%s|%s", 
            available, frozen, positionMargin, unrealizedPnl);
        
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * 验证余额一致性
     */
    public boolean validateBalance() {
        // available, frozen, position_margin都不能为负
        if (available != null && available.compareTo(BigDecimal.ZERO) < 0) return false;
        if (frozen != null && frozen.compareTo(BigDecimal.ZERO) < 0) return false;
        if (positionMargin != null && positionMargin.compareTo(BigDecimal.ZERO) < 0) return false;
        
        // 计算equity
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
        
        calculateEquity();
        
        // marginRatio = equity / maintenanceMargin
        BigDecimal marginRatio = equity.divide(maintenanceMargin, 4, BigDecimal.ROUND_DOWN);
        
        return marginRatio.compareTo(minRatio) < 0;
    }
}

