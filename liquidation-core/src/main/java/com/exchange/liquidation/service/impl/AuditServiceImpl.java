package com.exchange.liquidation.service.impl;

import com.alibaba.fastjson2.JSON;
import com.exchange.liquidation.entity.LiquidationAudit;
import com.exchange.liquidation.entity.LiquidationExecution;
import com.exchange.liquidation.mapper.LiquidationAuditMapper;
import com.exchange.liquidation.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 审计服务实现
 *
 * 异步记录所有强平操作，不阻塞主流程
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private final LiquidationAuditMapper auditMapper;

    @Override
    @Async("liquidationTaskExecutor")
    public void auditCreate(LiquidationExecution execution, String operatorType) {
        try {
            LiquidationAudit audit = new LiquidationAudit();
            audit.setAuditId(generateAuditId());
            audit.setLiquidationId(execution.getLiquidationId());
            audit.setOperation(LiquidationAudit.Operation.CREATE);
            audit.setOperatorId(null); // 系统自动操作
            audit.setOperatorType(operatorType);
            audit.setReason("Liquidation triggered by risk monitor");
            audit.setBeforeData(null);
            audit.setAfterData(JSON.toJSONString(execution));
            audit.setSuccess(1);
            audit.setCreatedAt(System.currentTimeMillis());

            auditMapper.insert(audit);

            log.info("📝 [AuditService] Audited CREATE, liquidationId={}, auditId={}",
                    execution.getLiquidationId(), audit.getAuditId());

        } catch (Exception e) {
            log.error("❌ [AuditService] Failed to audit CREATE, liquidationId={}",
                    execution.getLiquidationId(), e);
        }
    }

    @Override
    @Async("liquidationTaskExecutor")
    public void auditUpdate(LiquidationExecution before, LiquidationExecution after, String operatorType) {
        try {
            LiquidationAudit audit = new LiquidationAudit();
            audit.setAuditId(generateAuditId());
            audit.setLiquidationId(after.getLiquidationId());
            audit.setOperation(LiquidationAudit.Operation.UPDATE);
            audit.setOperatorId(null);
            audit.setOperatorType(operatorType);
            audit.setReason("Status changed: " + before.getStatus() + " -> " + after.getStatus());
            audit.setBeforeData(JSON.toJSONString(before));
            audit.setAfterData(JSON.toJSONString(after));
            audit.setSuccess(1);
            audit.setCreatedAt(System.currentTimeMillis());

            auditMapper.insert(audit);

            log.debug("📝 [AuditService] Audited UPDATE, liquidationId={}", after.getLiquidationId());

        } catch (Exception e) {
            log.error("❌ [AuditService] Failed to audit UPDATE, liquidationId={}",
                    after.getLiquidationId(), e);
        }
    }

    @Override
    @Async("liquidationTaskExecutor")
    public void auditCancel(LiquidationExecution execution, String reason, String operatorType) {
        try {
            LiquidationAudit audit = new LiquidationAudit();
            audit.setAuditId(generateAuditId());
            audit.setLiquidationId(execution.getLiquidationId());
            audit.setOperation(LiquidationAudit.Operation.CANCEL);
            audit.setOperatorId(null);
            audit.setOperatorType(operatorType);
            audit.setReason(reason);
            audit.setBeforeData(JSON.toJSONString(execution));
            audit.setAfterData(null);
            audit.setSuccess(1);
            audit.setCreatedAt(System.currentTimeMillis());

            auditMapper.insert(audit);

            log.info("📝 [AuditService] Audited CANCEL, liquidationId={}, reason={}",
                    execution.getLiquidationId(), reason);

        } catch (Exception e) {
            log.error("❌ [AuditService] Failed to audit CANCEL, liquidationId={}",
                    execution.getLiquidationId(), e);
        }
    }

    @Override
    @Async("liquidationTaskExecutor")
    public void auditRetry(LiquidationExecution execution, String reason) {
        try {
            LiquidationAudit audit = new LiquidationAudit();
            audit.setAuditId(generateAuditId());
            audit.setLiquidationId(execution.getLiquidationId());
            audit.setOperation(LiquidationAudit.Operation.RETRY);
            audit.setOperatorId(null);
            audit.setOperatorType(LiquidationAudit.OperatorType.AUTO);
            audit.setReason(reason);
            audit.setBeforeData(JSON.toJSONString(execution));
            audit.setAfterData(null);
            audit.setSuccess(1);
            audit.setCreatedAt(System.currentTimeMillis());

            auditMapper.insert(audit);

            log.info("📝 [AuditService] Audited RETRY, liquidationId={}, reason={}",
                    execution.getLiquidationId(), reason);

        } catch (Exception e) {
            log.error("❌ [AuditService] Failed to audit RETRY, liquidationId={}",
                    execution.getLiquidationId(), e);
        }
    }

    @Override
    @Async("liquidationTaskExecutor")
    public void auditFailure(String liquidationId, String operation, String errorMsg, String operatorType) {
        try {
            LiquidationAudit audit = new LiquidationAudit();
            audit.setAuditId(generateAuditId());
            audit.setLiquidationId(liquidationId);
            audit.setOperation(operation);
            audit.setOperatorId(null);
            audit.setOperatorType(operatorType);
            audit.setReason("Operation failed");
            audit.setBeforeData(null);
            audit.setAfterData(null);
            audit.setSuccess(0);
            audit.setErrorMsg(errorMsg);
            audit.setCreatedAt(System.currentTimeMillis());

            auditMapper.insert(audit);

            log.warn("📝 [AuditService] Audited FAILURE, liquidationId={}, operation={}, error={}",
                    liquidationId, operation, errorMsg);

        } catch (Exception e) {
            log.error("❌ [AuditService] Failed to audit FAILURE, liquidationId={}", liquidationId, e);
        }
    }

    /**
     * 生成审计ID
     */
    private String generateAuditId() {
        return "AUDIT_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
