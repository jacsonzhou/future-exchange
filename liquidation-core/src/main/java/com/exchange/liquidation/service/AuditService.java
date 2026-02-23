package com.exchange.liquidation.service;

import com.exchange.liquidation.entity.LiquidationExecution;

/**
 * 审计服务接口
 *
 * 记录所有强平操作，满足金融监管要求
 */
public interface AuditService {

    /**
     * 记录强平创建操作
     *
     * @param execution 强平执行记录
     * @param operatorType 操作类型: AUTO/MANUAL
     */
    void auditCreate(LiquidationExecution execution, String operatorType);

    /**
     * 记录强平更新操作
     *
     * @param before 更新前数据
     * @param after 更新后数据
     * @param operatorType 操作类型: AUTO/MANUAL
     */
    void auditUpdate(LiquidationExecution before, LiquidationExecution after, String operatorType);

    /**
     * 记录强平取消操作
     *
     * @param execution 强平执行记录
     * @param reason 取消原因
     * @param operatorType 操作类型: AUTO/MANUAL
     */
    void auditCancel(LiquidationExecution execution, String reason, String operatorType);

    /**
     * 记录强平重试操作
     *
     * @param execution 强平执行记录
     * @param reason 重试原因
     */
    void auditRetry(LiquidationExecution execution, String reason);

    /**
     * 记录操作失败
     *
     * @param liquidationId 强平ID
     * @param operation 操作类型
     * @param errorMsg 错误信息
     * @param operatorType 操作类型: AUTO/MANUAL
     */
    void auditFailure(String liquidationId, String operation, String errorMsg, String operatorType);
}
