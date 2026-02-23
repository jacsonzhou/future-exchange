package com.exchange.risk.service;

import com.exchange.risk.dto.CheckOrderRiskRequest;
import com.exchange.risk.dto.CheckOrderRiskResponse;

/**
 * Hard Risk Gate 核心服务接口
 * 
 * 职责：
 * 1. 同步风控检查
 * 2. 快速响应（P99 < 5ms）
 * 3. 强一致性
 * 4. 幂等保证
 */
public interface HardRiskService {
    
    /**
     * 检查订单风险（核心方法 - 增强版）
     * 
     * 🔥 核心改动：
     * 1. 接收 OMS 传递的 requiredMargin（预扣保证金）
     * 2. 接收 OMS 传递的 totalBalance（总余额）
     * 3. 检查"实际可用"余额 = totalBalance - 已预扣（从Redis）
     * 
     * 风控检查项：
     * 1. 可用保证金校验（考虑预扣）
     * 2. 杠杆限制
     * 3. 最大仓位限制
     * 4. ReduceOnly规则
     * 5. 账户状态
     * 6. 价格有效性
     * 7. 黑白名单
     * 
     * @param request 风控检查请求
     * @param requiredMargin 所需保证金（单位：分），可选
     * @param totalBalance 用户总余额（单位：分），可选
     * @return 风控检查响应
     */
    CheckOrderRiskResponse checkOrderRisk(
        CheckOrderRiskRequest request, 
        Long requiredMargin, 
        Long totalBalance
    );
    
    /**
     * 检查订单风险（兼容旧版本）
     * 
     * @param request 风控检查请求
     * @return 风控检查响应
     */
    default CheckOrderRiskResponse checkOrderRisk(CheckOrderRiskRequest request) {
        return checkOrderRisk(request, null, null);
    }
}
