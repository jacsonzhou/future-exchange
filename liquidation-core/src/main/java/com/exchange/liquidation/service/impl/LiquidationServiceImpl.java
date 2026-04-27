package com.exchange.liquidation.service.impl;

import com.exchange.common.core.IdGenerator;
import com.exchange.liquidation.dto.*;
import com.exchange.liquidation.entity.LiquidationExecution;
import com.exchange.liquidation.mapper.LiquidationExecutionMapper;
import com.exchange.liquidation.producer.LiquidationEventProducer;
import com.exchange.liquidation.service.*;
import com.exchange.liquidation.client.OmsClient;
import com.exchange.liquidation.client.PositionClient;
import com.exchange.liquidation.util.OmsResponseUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 强平服务实现
 * 
 * 完整流程：
 * 1. 幂等性检查
 * 2. 保存执行记录
 * 3. 创建强平订单
 * 4. 开始监控订单
 * 5. 订单成交后：计算盈亏、处理保险基金、发布完成事件
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiquidationServiceImpl implements LiquidationService {

    private static final String MODE_MATCH_ENGINE = "MATCH_ENGINE";
    private static final String MODE_CFD_DEALER = "CFD_DEALER";
    private static final HexFormat HEX = HexFormat.of();
    
    private final LiquidationExecutionMapper executionMapper;
    private final LiquidationEventProducer eventProducer;
    private final StringRedisTemplate redisTemplate;
    private final OmsClient omsClient;
    private final PositionClient positionClient;
    private final OrderMonitorService orderMonitorService;
    private final PnLCalculatorService pnLCalculatorService;
    private final InsuranceFundService insuranceFundService;
    
    @Value("${liquidation.idempotency.window-minutes:5}")
    private int idempotencyWindowMinutes;
    
    @Value("${liquidation.idempotency.redis-key-prefix:liquidation:idempotency:}")
    private String idempotencyKeyPrefix;

    @Value("${liquidation.idempotency.dedup-enabled:true}")
    private boolean idempotencyDedupEnabled;
    
    @Value("${liquidation.execution.max-retry:3}")
    private int maxRetry;

    @Value("${liquidation.order.execution-mode:CFD_DEALER}")
    private String liquidationOrderExecutionMode;
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void processLiquidation(LiquidationTriggerEvent event) {
        if (event == null || event.getPositionId() == null) {
            log.warn("[LiquidationService] Ignore invalid liquidation trigger event: {}", event);
            return;
        }

        String dedupKey = buildDedupKey(event);
        String liquidationId = generateLiquidationId(event, dedupKey);

        log.info("[LiquidationService] Processing liquidation, liquidationId={}, dedupKey={}, userId={}, positionId={}, triggerType={}, sequence={}",
                liquidationId, dedupKey, event.getUserId(), event.getPositionId(), event.getTriggerType(), event.getSequence());
        
        try {
            // 1. 幂等性检查
            if (!checkIdempotency(event, liquidationId, dedupKey)) {
                log.warn("[LiquidationService] Duplicate liquidation ignored, liquidationId={}, dedupKey={}",
                        liquidationId, dedupKey);
                return;
            }

            // 2. 保存记录
            LiquidationExecution execution = createExecution(liquidationId, event);

            // 3. 创建订单
            Long orderId = createOrder(liquidationId, event);
            execution.setOrderId(orderId);
            execution.setStatus("SUBMITTED");
            execution.setSubmittedAt(System.currentTimeMillis());
            execution.setUpdatedAt(System.currentTimeMillis());
            executionMapper.updateById(execution);
            
            // 4. 开始监控订单
            orderMonitorService.startMonitoring(liquidationId, orderId);
            
            log.info("[LiquidationService] Liquidation submitted, liquidationId={}, orderId={}", 
                    liquidationId, orderId);
        } catch (DuplicateKeyException duplicateKeyException) {
            log.warn("[LiquidationService] Duplicate liquidation ignored by DB unique key, liquidationId={}, dedupKey={}",
                    liquidationId, dedupKey);
            return;
        } catch (Exception e) {
            log.error("[LiquidationService] Failed, liquidationId={}", liquidationId, e);
            handleFailure(liquidationId, e.getMessage());
        }
    }
    
    /**
     * 处理订单完全成交后的逻辑
     * 
     * 1. 计算盈亏
     * 2. 处理保险基金
     * 3. 发布完成事件
     * 
     * 使用异步处理，避免阻塞订单状态消费
     */
    @Async("liquidationTaskExecutor")
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void processFilledLiquidation(String liquidationId) {
        log.info("[LiquidationService] Processing filled liquidation, liquidationId={}", liquidationId);
        
        LiquidationExecution execution = executionMapper.selectByLiquidationId(liquidationId);
        if (execution == null) {
            log.error("[LiquidationService] LiquidationExecution not found, liquidationId={}", liquidationId);
            return;
        }
        if ("COMPLETED".equalsIgnoreCase(execution.getStatus())) {
            log.info("[LiquidationService] Filled liquidation already completed, skip duplicate callback, liquidationId={}",
                    liquidationId);
            return;
        }
        if ("FAILED".equalsIgnoreCase(execution.getStatus()) || "CANCELLED".equalsIgnoreCase(execution.getStatus())) {
            log.warn("[LiquidationService] Skip filled callback for terminal state, liquidationId={}, status={}",
                    liquidationId, execution.getStatus());
            return;
        }
        
        try {
            // 1. 计算盈亏
            // 如果有部分成交的累计盈亏，需要使用增量计算
            Long partialPnl = execution.getPartialPnl();
            Long partialBankruptLoss = execution.getPartialBankruptLoss();

            if (partialPnl != null && partialPnl != 0) {
                log.info("📊 [LiquidationService] Found partial fill data, " +
                        "liquidationId={}, partialPnl={}, partialBankruptLoss={}",
                        liquidationId, partialPnl, partialBankruptLoss);

                // 计算最后一次成交的盈亏
                execution = pnLCalculatorService.calculatePnL(execution);

                // 总盈亏 = 部分成交累计盈亏 + 最后一次盈亏
                Long lastPnl = execution.getRealizedPnl();
                Long totalPnl = partialPnl + (lastPnl != null ? lastPnl : 0L);
                execution.setRealizedPnl(totalPnl);

                // 总穿仓损失 = 部分成交累计穿仓损失 + 最后一次穿仓损失
                Long lastBankruptLoss = execution.getBankruptLoss();
                Long totalBankruptLoss = (partialBankruptLoss != null ? partialBankruptLoss : 0L) +
                                         (lastBankruptLoss != null ? lastBankruptLoss : 0L);
                execution.setBankruptLoss(totalBankruptLoss);

                log.info("✅ [LiquidationService] Total PnL calculated with partial fills, " +
                        "liquidationId={}, totalPnl={}, totalBankruptLoss={}",
                        liquidationId, totalPnl, totalBankruptLoss);

            } else {
                // 没有部分成交，直接计算完整盈亏
                execution = pnLCalculatorService.calculatePnL(execution);
                log.info("✅ [LiquidationService] PnL calculated without partial fills, " +
                        "liquidationId={}, realizedPnl={}, bankruptLoss={}",
                        liquidationId, execution.getRealizedPnl(), execution.getBankruptLoss());
            }

            executionMapper.updateById(execution);

            // 2. 处理保险基金（如果有穿仓损失）
            if (execution.getBankruptLoss() != null && execution.getBankruptLoss() > 0) {
                // 如果已经有部分赔付，需要减去已赔付金额
                Long alreadyCovered = execution.getInsuranceCover() != null ?
                    execution.getInsuranceCover() : 0L;

                Long remainingBankruptLoss = execution.getBankruptLoss() - alreadyCovered;

                if (remainingBankruptLoss > 0) {
                    log.info("💰 [LiquidationService] Applying remaining insurance cover, " +
                            "liquidationId={}, totalBankruptLoss={}, alreadyCovered={}, remainingBankruptLoss={}",
                            liquidationId, execution.getBankruptLoss(), alreadyCovered, remainingBankruptLoss);

                    // 申请剩余部分的保险基金赔付
                    // 临时修改 bankruptLoss 为剩余损失
                    Long originalBankruptLoss = execution.getBankruptLoss();
                    execution.setBankruptLoss(remainingBankruptLoss);

                    Long additionalInsuranceCover = insuranceFundService.applyInsuranceCover(execution);

                    // 恢复原始穿仓损失
                    execution.setBankruptLoss(originalBankruptLoss);

                    // 累加保险基金赔付
                    Long totalInsuranceCover = alreadyCovered + additionalInsuranceCover;
                    execution.setInsuranceCover(totalInsuranceCover);

                    log.info("✅ [LiquidationService] Total insurance cover applied, " +
                            "liquidationId={}, totalInsuranceCover={}",
                            liquidationId, totalInsuranceCover);

                } else {
                    log.info("ℹ️ [LiquidationService] All bankrupt loss already covered by partial insurance, " +
                            "liquidationId={}, totalBankruptLoss={}, totalCovered={}",
                            liquidationId, execution.getBankruptLoss(), alreadyCovered);
                }

                // 重新计算剩余穿仓损失
                Long totalInsuranceCover = execution.getInsuranceCover() != null ?
                    execution.getInsuranceCover() : 0L;
                long remainingLoss = execution.getBankruptLoss() - totalInsuranceCover;
                execution.setRemainingLoss(Math.max(remainingLoss, 0));
                execution.setAdlRequired(execution.getRemainingLoss() > 0 ? 1 : 0);

                executionMapper.updateById(execution);
            }

            // 2.5 强平盈余注资保险基金（幂等由 insurance-fund bizSeq 保证）
            Long surplusInjected = insuranceFundService.injectLiquidationSurplus(execution);
            if (surplusInjected != null && surplusInjected > 0) {
                log.info("💸 [LiquidationService] Liquidation surplus injected to insurance fund, " +
                                "liquidationId={}, injected={}",
                        liquidationId, surplusInjected);
            }
            
            // 3. 更新状态为完成
            execution.setStatus("COMPLETED");
            execution.setCompletedAt(System.currentTimeMillis());
            execution.setUpdatedAt(System.currentTimeMillis());
            executionMapper.updateById(execution);
            
            // 4. 发布完成事件
            LiquidationCompletedEvent completedEvent = buildCompletedEvent(execution);
            eventProducer.publishLiquidationCompleted(completedEvent);
            
            log.info("[LiquidationService] ✅ Liquidation completed, liquidationId={}, " +
                    "realizedPnl={}, bankruptLoss={}, insuranceCover={}, adlRequired={}",
                    liquidationId, execution.getRealizedPnl(), execution.getBankruptLoss(),
                    execution.getInsuranceCover(), execution.getAdlRequired());
            
        } catch (Exception e) {
            log.error("[LiquidationService] ❌ Failed to process filled liquidation, " +
                    "liquidationId={}", liquidationId, e);
            execution.setStatus("FAILED");
            execution.setErrorMsg("Process filled liquidation failed: " + e.getMessage());
            execution.setUpdatedAt(System.currentTimeMillis());
            executionMapper.updateById(execution);
        }
    }
    
    /**
     * 构建完成事件
     */
    private LiquidationCompletedEvent buildCompletedEvent(LiquidationExecution execution) {
        return LiquidationCompletedEvent.builder()
                .eventType("LIQUIDATION_COMPLETED")
                .liquidationId(execution.getLiquidationId())
                .userId(execution.getUserId())
                .positionId(execution.getPositionId())
                .symbol(execution.getSymbol())
                .side(execution.getPositionSide() == 1 ? "LONG" : "SHORT")
                .marginMode(execution.getMarginMode())
                .isBankrupt(execution.getBankruptLoss() != null && execution.getBankruptLoss() > 0)
                .bankruptPrice(execution.getBankruptcyPrice())
                .bankruptQty(execution.getExecutedQty())
                .bankruptLoss(execution.getBankruptLoss())
                .insuranceCover(execution.getInsuranceCover())
                .remainingLoss(execution.getRemainingLoss())
                .adlRequired(execution.getAdlRequired() != null && execution.getAdlRequired() == 1)
                .executedPrice(execution.getExecutedPrice())
                .executedQty(execution.getExecutedQty())
                .realizedPnl(execution.getRealizedPnl())
                .timestamp(System.currentTimeMillis())
                .sequence(IdGenerator.generate())
                .build();
    }
    
    /**
     * 幂等性检查
     *
     * 双重保障：
     * 1. 先检查数据库（持久化保证，不会因Redis故障丢失）
     * 2. 再用Redis加速（性能优化）
     *
     * @param event 触发事件
     * @param liquidationId 幂等生成的强平ID
     * @param dedupKey 幂等键（positionId:triggerType:sequence）
     * @return true=可以处理, false=重复请求
     */
    private boolean checkIdempotency(LiquidationTriggerEvent event, String liquidationId, String dedupKey) {
        if (!idempotencyDedupEnabled) {
            return true;
        }

        // 1. 先查业务唯一ID（同一幂等键会映射到同一个 liquidationId）
        LiquidationExecution byId = executionMapper.selectByLiquidationId(liquidationId);
        if (byId != null) {
            log.warn("⚠️ [LiquidationService] Duplicate liquidation found by liquidationId, liquidationId={}, status={}",
                    liquidationId, byId.getStatus());
            return false;
        }

        // 2. 用 positionId + triggerType + triggeredAt 做持久化兜底
        long triggerTime = event.getTimestamp() == null ? 0L : event.getTimestamp();
        if (triggerTime > 0) {
            LiquidationExecution sameTrigger = executionMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LiquidationExecution>()
                            .eq(LiquidationExecution::getPositionId, event.getPositionId())
                            .eq(LiquidationExecution::getTriggerType, normalizeTriggerType(event.getTriggerType()))
                            .eq(LiquidationExecution::getTriggeredAt, triggerTime)
                            .orderByDesc(LiquidationExecution::getCreatedAt)
                            .last("LIMIT 1")
            );
            if (sameTrigger != null) {
                log.warn("⚠️ [LiquidationService] Duplicate liquidation found by trigger triple, " +
                                "positionId={}, triggerType={}, triggeredAt={}, existingLiquidationId={}",
                        event.getPositionId(), normalizeTriggerType(event.getTriggerType()), triggerTime, sameTrigger.getLiquidationId());
                return false;
            }
        } else {
            long windowStart = System.currentTimeMillis() - (idempotencyWindowMinutes * 60 * 1000L);
            LiquidationExecution fallback = executionMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LiquidationExecution>()
                            .eq(LiquidationExecution::getPositionId, event.getPositionId())
                            .ge(LiquidationExecution::getCreatedAt, windowStart)
                            .orderByDesc(LiquidationExecution::getCreatedAt)
                            .last("LIMIT 1")
            );
            if (fallback != null) {
                log.warn("⚠️ [LiquidationService] Duplicate liquidation found in fallback window, " +
                                "positionId={}, existingLiquidationId={}, createdAt={}",
                        event.getPositionId(), fallback.getLiquidationId(), fallback.getCreatedAt());
                return false;
            }
        }

        // 3. Redis 加速（防并发）
        String key = idempotencyKeyPrefix + dedupKey;
        try {
            Boolean success = redisTemplate.opsForValue()
                    .setIfAbsent(key, liquidationId, idempotencyWindowMinutes, TimeUnit.MINUTES);
            if (!Boolean.TRUE.equals(success)) {
                log.warn("⚠️ [LiquidationService] Duplicate liquidation detected by Redis, dedupKey={}", dedupKey);
                return false;
            }
        } catch (Exception e) {
            // Redis 故障时依赖数据库幂等键和唯一约束
            log.warn("⚠️ [LiquidationService] Redis idempotency check failed, fallback to DB only, dedupKey={}",
                    dedupKey, e);
        }
        return true;
    }
    
    private String generateLiquidationId(LiquidationTriggerEvent event, String dedupKey) {
        long positionId = event.getPositionId() == null ? 0L : event.getPositionId();
        String triggerType = normalizeTriggerType(event.getTriggerType());
        String hash = shortHash(dedupKey);
        return "LIQ_" + triggerType + "_" + positionId + "_" + hash;
    }

    private String buildDedupKey(LiquidationTriggerEvent event) {
        long positionId = event.getPositionId() == null ? 0L : event.getPositionId();
        String triggerType = normalizeTriggerType(event.getTriggerType());
        long sequence = resolveSequence(event);
        return positionId + ":" + triggerType + ":" + sequence;
    }

    private long resolveSequence(LiquidationTriggerEvent event) {
        if (event.getSequence() != null && event.getSequence() > 0) {
            return event.getSequence();
        }
        if (event.getTimestamp() != null && event.getTimestamp() > 0) {
            return event.getTimestamp();
        }
        return System.currentTimeMillis();
    }

    private String normalizeTriggerType(String triggerType) {
        if (triggerType == null || triggerType.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = triggerType.trim().toUpperCase(Locale.ROOT);
        return normalized.length() > 24 ? normalized.substring(0, 24) : normalized;
    }

    private String shortHash(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            String hash = HEX.formatHex(digest);
            return hash.length() > 16 ? hash.substring(0, 16) : hash;
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
    
    private LiquidationExecution createExecution(String liquidationId, LiquidationTriggerEvent event) {
        long now = System.currentTimeMillis();
        
        LiquidationExecution e = new LiquidationExecution();
        e.setLiquidationId(liquidationId);
        e.setUserId(event.getUserId());
        e.setPositionId(event.getPositionId());
        e.setSymbol(event.getSymbol());
        e.setMarginMode(event.getMarginMode());
        e.setPositionSide(event.getPositionSide());
        e.setTriggerType(normalizeTriggerType(event.getTriggerType()));
        Long triggerPrice = event.getMarkPrice();
        if (triggerPrice == null || triggerPrice <= 0) {
            triggerPrice = event.getLiquidationPrice() != null ? event.getLiquidationPrice() : 0L;
            log.warn("[LiquidationService] markPrice missing, fallback to liquidationPrice={}, liquidationId={}",
                    triggerPrice, liquidationId);
        }
        e.setTriggerPrice(triggerPrice);
        e.setMarginRatio(event.getMarginRatio());
        e.setQuantity(event.getPositionQty());
        e.setSide(event.getCloseSide());
        e.setOrderType("MARKET");
        e.setEntryPrice(event.getEntryPrice());
        e.setBankruptcyPrice(event.getBankruptcyPrice());
        e.setMarkPrice(event.getMarkPrice());
        e.setInitialMargin(event.getCurrentMargin());
        e.setMaintenanceMargin(event.getMaintenanceMargin());
        e.setStatus("PENDING");
        e.setRetryCount(0);
        e.setTriggeredAt(event.getTimestamp() != null ? event.getTimestamp() : now);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        executionMapper.insert(e);
        return e;
    }
    
    private Long createOrder(String liquidationId, LiquidationTriggerEvent event) {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setUserId(event.getUserId());
        req.setSymbol(event.getSymbol());
        req.setSide(event.getCloseSide());
        req.setOrderType("MARKET");
        req.setQuantity(event.getPositionQty());
        req.setReduceOnly(true);
        req.setOrderSource("LIQUIDATION");
        req.setPositionId(event.getPositionId());
        req.setLiquidationId(liquidationId);
        req.setExecutionMode(resolveExecutionMode(liquidationOrderExecutionMode));

        Object raw = omsClient.createOrder(req);
        Long orderId = OmsResponseUtil.extractOrderId(raw);
        if (orderId == null) {
            throw new IllegalStateException(
                "OMS create liquidation order failed, liquidationId=" + liquidationId + ", "
                    + OmsResponseUtil.extractErrorMessage(raw)
            );
        }
        return orderId;
    }

    private String resolveExecutionMode(String rawMode) {
        if (rawMode == null || rawMode.isBlank()) {
            return MODE_CFD_DEALER;
        }
        String normalized = rawMode.trim().toUpperCase();
        if (MODE_CFD_DEALER.equals(normalized) || MODE_MATCH_ENGINE.equals(normalized)) {
            return normalized;
        }
        log.warn("[LiquidationService] invalid liquidation.order.execution-mode={}, fallback {}",
            rawMode, MODE_CFD_DEALER);
        return MODE_CFD_DEALER;
    }

    private void handleFailure(String liquidationId, String error) {
        LiquidationExecution execution = executionMapper.selectByLiquidationId(liquidationId);
        if (execution != null) {
            execution.setStatus("FAILED");
            execution.setErrorMsg(trimError(error));
            execution.setUpdatedAt(System.currentTimeMillis());
            try {
                int updated = executionMapper.updateById(execution);
                if (updated > 0) {
                    return;
                }
                log.warn("[LiquidationService] updateById returned 0, fallback update by liquidationId, liquidationId={}",
                    liquidationId);
            } catch (Exception updateEx) {
                log.error("[LiquidationService] updateById failed on handleFailure, liquidationId={}",
                    liquidationId, updateEx);
            }

            int fallback = executionMapper.updateFailureStateByLiquidationId(
                liquidationId,
                "FAILED",
                trimError(error),
                System.currentTimeMillis()
            );
            if (fallback <= 0) {
                log.error("[LiquidationService] fallback failure update affected 0 rows, liquidationId={}", liquidationId);
            }
        }
    }

    private String trimError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 512 ? error : error.substring(0, 512);
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String manualLiquidation(Long userId, Long positionId, String reason) {
        log.info("[LiquidationService] Manual liquidation, userId={}, positionId={}, reason={}",
                userId, positionId, reason);

        // 1. 查询仓位信息
        PositionInfo position = positionClient.getPosition(positionId);
        if (position == null) {
            throw new IllegalArgumentException("Position not found, positionId=" + positionId);
        }
        if (position.getQuantity() == null || position.getQuantity() <= 0) {
            throw new IllegalArgumentException("Invalid position quantity, positionId=" + positionId);
        }

        // 2. 构建触发事件
        LiquidationTriggerEvent event = new LiquidationTriggerEvent();
        event.setUserId(userId);
        event.setPositionId(positionId);
        event.setSymbol(position.getSymbol());
        event.setMarginMode(position.getMarginMode());
        event.setTriggerType("MANUAL");
        event.setPositionSide("LONG".equalsIgnoreCase(position.getSide()) ? 1 : 2);
        event.setPositionQty(position.getQuantity());
        event.setEntryPrice(position.getEntryPrice());
        event.setMarkPrice(position.getMarkPrice());
        event.setLiquidationPrice(position.getLiquidationPrice());
        event.setBankruptcyPrice(position.getBankruptcyPrice());
        event.setLeverage(position.getLeverage());
        event.setTimestamp(System.currentTimeMillis());
        event.setSequence(IdGenerator.generate());

        // 3. 处理强平
        processLiquidation(event);

        String liquidationId = buildDedupKey(event);
        log.info("[LiquidationService] Manual liquidation submitted, userId={}, positionId={}, liquidationId={}",
                userId, positionId, liquidationId);
        return liquidationId;
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean retryLiquidation(String liquidationId) {
        log.info("[LiquidationService] Retrying liquidation, liquidationId={}", liquidationId);

        LiquidationExecution execution = executionMapper.selectByLiquidationId(liquidationId);
        if (execution == null) {
            log.warn("[LiquidationService] LiquidationExecution not found, liquidationId={}", liquidationId);
            return false;
        }

        // 检查是否可以重试
        if (!execution.canRetry(maxRetry)) {
            log.warn("[LiquidationService] Cannot retry, liquidationId={}, retryCount={}, maxRetry={}",
                    liquidationId, execution.getRetryCount(), maxRetry);
            return false;
        }

        // 取消旧订单（如果存在）
        if (execution.getOrderId() != null) {
            try {
                omsClient.cancelOrder(execution.getOrderId());
                log.info("[LiquidationService] Old order cancelled, liquidationId={}, orderId={}",
                        liquidationId, execution.getOrderId());
            } catch (Exception e) {
                log.warn("[LiquidationService] Failed to cancel old order, liquidationId={}, orderId={}, error={}",
                        liquidationId, execution.getOrderId(), e.getMessage());
            }
        }

        // 重新创建订单
        CreateOrderRequest req = new CreateOrderRequest();
        req.setUserId(execution.getUserId());
        req.setSymbol(execution.getSymbol());
        req.setSide(execution.getSide());
        req.setOrderType("MARKET");
        req.setQuantity(execution.getQuantity());
        req.setReduceOnly(true);
        req.setOrderSource("LIQUIDATION_RETRY");
        req.setPositionId(execution.getPositionId());
        req.setLiquidationId(liquidationId);

        Object raw = omsClient.createOrder(req);
        Long orderId = OmsResponseUtil.extractOrderId(raw);
        if (orderId == null) {
            log.error("[LiquidationService] Retry create order failed, liquidationId={}, {}",
                    liquidationId, OmsResponseUtil.extractErrorMessage(raw));
            execution.setStatus("FAILED");
            execution.setErrorMsg("Retry create order failed: " + OmsResponseUtil.extractErrorMessage(raw));
            execution.setUpdatedAt(System.currentTimeMillis());
            executionMapper.updateById(execution);
            return false;
        }

        // 更新执行记录
        execution.setOrderId(orderId);
        execution.setRetryCount(execution.getRetryCount() + 1);
        execution.setStatus("SUBMITTED");
        execution.setErrorMsg(null);
        execution.setSubmittedAt(System.currentTimeMillis());
        execution.setUpdatedAt(System.currentTimeMillis());
        executionMapper.updateById(execution);

        // 启动监控
        orderMonitorService.startMonitoring(liquidationId, orderId);

        log.info("[LiquidationService] Retry successful, liquidationId={}, retryCount={}, orderId={}",
                liquidationId, execution.getRetryCount(), orderId);
        return true;
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelLiquidation(String liquidationId) {
        log.info("[LiquidationService] Cancelling liquidation, liquidationId={}", liquidationId);
        
        LiquidationExecution execution = executionMapper.selectByLiquidationId(liquidationId);
        if (execution == null) {
            log.warn("[LiquidationService] LiquidationExecution not found, liquidationId={}", liquidationId);
            return false;
        }
        
        // 检查是否可以取消
        if (!"PENDING".equals(execution.getStatus()) && 
            !"SUBMITTED".equals(execution.getStatus()) &&
            !"PARTIALLY_FILLED".equals(execution.getStatus())) {
            log.warn("[LiquidationService] Cannot cancel, liquidationId={}, status={}",
                    liquidationId, execution.getStatus());
            return false;
        }
        
        // 取消订单（如果已提交）
        if (execution.getOrderId() != null) {
            try {
                omsClient.cancelOrder(execution.getOrderId());
                log.info("[LiquidationService] Order cancelled, liquidationId={}, orderId={}",
                        liquidationId, execution.getOrderId());
            } catch (Exception e) {
                log.error("[LiquidationService] Failed to cancel order, liquidationId={}, orderId={}",
                        liquidationId, execution.getOrderId(), e);
            }
        }
        
        // 更新状态
        execution.setStatus("CANCELLED");
        execution.setUpdatedAt(System.currentTimeMillis());
        executionMapper.updateById(execution);
        
        // 停止监控
        orderMonitorService.stopMonitoring(liquidationId);
        
        log.info("[LiquidationService] Liquidation cancelled, liquidationId={}", liquidationId);
        return true;
    }
}
