package com.exchange.liquidation.service;

import com.exchange.liquidation.dto.LiquidationTriggerEvent;

/**
 * 强平服务接口
 */
public interface LiquidationService {
    
    /**
     * 处理强平触发事件
     * 
     * 完整流程：
     * 1. 幂等性检查
     * 2. 保存执行记录
     * 3. 创建强平订单
     * 4. 监控订单执行
     * 5. 计算盈亏
     * 6. 处理保险基金
     * 7. 发布完成事件
     * 
     * @param event 强平触发事件
     */
    void processLiquidation(LiquidationTriggerEvent event);
    
    /**
     * 手动触发强平
     * 
     * @param userId 用户ID
     * @param positionId 仓位ID
     * @param reason 强平原因
     * @return 强平ID
     */
    String manualLiquidation(Long userId, Long positionId, String reason);
    
    /**
     * 重试失败的强平
     * 
     * @param liquidationId 强平ID
     * @return 是否成功
     */
    boolean retryLiquidation(String liquidationId);
    
    /**
     * 取消进行中的强平
     * 
     * @param liquidationId 强平ID
     * @return 是否成功
     */
    boolean cancelLiquidation(String liquidationId);
}
