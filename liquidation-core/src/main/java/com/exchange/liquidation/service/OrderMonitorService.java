package com.exchange.liquidation.service;

import com.exchange.liquidation.entity.LiquidationExecution;

/**
 * 订单监控服务接口
 * 
 * 监控强平订单的执行状态，处理部分成交、超时等情况
 */
public interface OrderMonitorService {
    
    /**
     * 开始监控订单
     * 
     * @param liquidationId 强平ID
     * @param orderId 订单ID
     */
    void startMonitoring(String liquidationId, Long orderId);
    
    /**
     * 处理订单状态变更
     * 
     * @param orderId 订单ID
     * @param status 订单状态
     * @param filledQty 已成交数量
     * @param avgPrice 平均成交价格
     */
    void handleOrderStatusChange(Long orderId, String status, Long filledQty, Long avgPrice);
    
    /**
     * 检查超时订单
     * 
     * 定时任务调用，检查是否有订单超时
     */
    void checkTimeoutOrders();
    
    /**
     * 取消监控
     * 
     * @param liquidationId 强平ID
     */
    void stopMonitoring(String liquidationId);
}




