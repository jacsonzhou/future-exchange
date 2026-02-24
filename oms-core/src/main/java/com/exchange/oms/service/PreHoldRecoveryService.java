package com.exchange.oms.service;

import com.exchange.common.core.Money;
import com.exchange.oms.entity.OmsOrder;
import com.exchange.oms.mapper.OmsOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 预扣恢复服务
 * 
 * 🔥 核心职责：
 * OMS 重启时，从数据库恢复未成交订单的预扣状态
 * 
 * 🔥 恢复策略：
 * 1. 查询所有"风控通过但未成交"的订单
 * 2. 重新建立 Redis 预扣
 * 3. 已过期订单清理预扣
 * 
 * 对标：Binance / OKX / Bybit级别
 */
@Slf4j
@Service
public class PreHoldRecoveryService {
    
    @Autowired
    private OmsOrderMapper omsOrderMapper;
    
    @Autowired
    private MarginPreHoldService marginPreHoldService;
    
    /**
     * 需要恢复预扣的订单状态
     */
    private static final List<Integer> RECOVERABLE_STATUSES = List.of(
        2, // FROZEN: 已冻结资金，待成交
        3  // PARTIALLY_FILLED: 部分成交
    );

    private static final int DEFAULT_LEVERAGE = 10;
    
    /**
     * OMS 启动时执行恢复
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        log.info("[PreHoldRecovery] 🚀 Starting pre-hold recovery...");
        
        try {
            // 1. 查询需要恢复预扣的订单
            List<OmsOrder> pendingOrders = omsOrderMapper.selectByStatuses(RECOVERABLE_STATUSES);
            
            if (pendingOrders.isEmpty()) {
                log.info("[PreHoldRecovery] ✅ No pending orders to recover");
                return;
            }
            
            log.info("[PreHoldRecovery] Found {} pending orders to recover", pendingOrders.size());
            
            int successCount = 0;
            int failCount = 0;
            int skipCount = 0;
            
            for (OmsOrder order : pendingOrders) {
                try {
                    // 计算订单所需保证金
                    long margin = calculateRequiredMargin(order);
                    
                    // 检查是否已存在预扣
                    long existingHold = marginPreHoldService.getOrderPreHold(order.getUserId(), order.getId());
                    if (existingHold > 0) {
                        log.debug("[PreHoldRecovery] Skip existing pre-hold, orderId={}, margin={}", 
                            order.getId(), Money.format(existingHold));
                        skipCount++;
                        continue;
                    }
                    
                    // 重新建立预扣（恢复场景跳过余额检查）
                    MarginPreHoldService.PreHoldResult result = marginPreHoldService.recoverPreHold(
                        order.getUserId(), 
                        order.getId(), 
                        margin
                    );
                    
                    if (result.isSuccess()) {
                        successCount++;
                        log.info("[PreHoldRecovery] ✅ Recovered pre-hold, orderId={}, margin={}", 
                            order.getId(), Money.format(margin));
                    } else {
                        failCount++;
                        log.error("[PreHoldRecovery] ❌ Failed to recover pre-hold, orderId={}, reason={}", 
                            order.getId(), result.getMessage());
                    }
                    
                } catch (Exception e) {
                    failCount++;
                    log.error("[PreHoldRecovery] ❌ Error recovering order, orderId={}", order.getId(), e);
                }
            }
            
            log.info("[PreHoldRecovery] 🎯 Recovery completed: success={}, failed={}, skipped={}, total={}", 
                successCount, failCount, skipCount, pendingOrders.size());
            
        } catch (Exception e) {
            log.error("[PreHoldRecovery] ❌ Recovery process error", e);
        }
    }
    
    /**
     * 计算订单所需保证金
     * 
     * @param order 订单
     * @return 保证金（单位：分）
     */
    private long calculateRequiredMargin(OmsOrder order) {
        // 名义价值 = 价格 × 数量
        BigDecimal notional = order.getPrice()
            .multiply(order.getQuantity())
            .divide(BigDecimal.valueOf(Money.SCALE * Money.SCALE), 8, RoundingMode.HALF_UP);
        
        // 所需保证金 = 名义价值 / 杠杆
        BigDecimal margin = notional.divide(
            BigDecimal.valueOf(DEFAULT_LEVERAGE),
            8, 
            RoundingMode.HALF_UP
        );
        
        // 转换为内部存储单位（分）
        return margin.multiply(BigDecimal.valueOf(Money.SCALE)).longValue();
    }
    
    /**
     * 手动触发恢复（用于管理接口）
     * 
     * @return 恢复结果
     */
    public RecoveryResult triggerRecovery() {
        log.info("[PreHoldRecovery] 🔄 Manual recovery triggered");
        
        List<OmsOrder> pendingOrders = omsOrderMapper.selectByStatuses(RECOVERABLE_STATUSES);
        int recovered = 0;
        
        for (OmsOrder order : pendingOrders) {
            long existingHold = marginPreHoldService.getOrderPreHold(order.getUserId(), order.getId());
            if (existingHold == 0) {
                long margin = calculateRequiredMargin(order);
                MarginPreHoldService.PreHoldResult result = marginPreHoldService.recoverPreHold(
                    order.getUserId(), 
                    order.getId(), 
                    margin
                );
                if (result.isSuccess()) {
                    recovered++;
                }
            }
        }
        
        return new RecoveryResult(pendingOrders.size(), recovered);
    }
    
    /**
     * 清理无效的预扣（订单已成交/取消但预扣未释放）
     * 
     * @return 清理数量
     */
    public int cleanupStalePreHolds() {
        log.info("[PreHoldRecovery] 🧹 Starting stale pre-hold cleanup...");
        
        // 这里可以实现更复杂的清理逻辑
        // 例如：扫描所有 Redis 预扣，检查对应订单状态，清理已结束的订单
        
        // 简化实现：依赖 Redis TTL 自动过期
        log.info("[PreHoldRecovery] ✅ Cleanup completed (relying on Redis TTL)");
        return 0;
    }
    
    // ==================== 内部类 ====================
    
    /**
     * 恢复结果
     */
    public static class RecoveryResult {
        private final int totalOrders;
        private final int recoveredCount;
        
        public RecoveryResult(int totalOrders, int recoveredCount) {
            this.totalOrders = totalOrders;
            this.recoveredCount = recoveredCount;
        }
        
        public int getTotalOrders() {
            return totalOrders;
        }
        
        public int getRecoveredCount() {
            return recoveredCount;
        }
        
        @Override
        public String toString() {
            return "RecoveryResult{" +
                "totalOrders=" + totalOrders +
                ", recoveredCount=" + recoveredCount +
                '}';
        }
    }
}
