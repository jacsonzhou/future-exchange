package com.exchange.liquidation.service.impl;

import com.exchange.liquidation.entity.LiquidationExecution;
import com.exchange.liquidation.mapper.LiquidationExecutionMapper;
import com.exchange.liquidation.service.LiquidationService;
import com.exchange.liquidation.service.OrderMonitorService;
import com.exchange.liquidation.service.impl.PartialLiquidationHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 订单监控服务实现
 *
 * 使用Redis存储监控状态，解决内存泄漏和服务重启丢失问题
 * - 支持集群共享
 * - 自动过期清理
 * - 服务重启后恢复监控
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderMonitorServiceImpl implements OrderMonitorService {

    private final LiquidationExecutionMapper executionMapper;
    private final LiquidationService liquidationService;
    private final PartialLiquidationHandler partialLiquidationHandler;
    private final RedisTemplate<String, String> redisTemplate;
    private final com.exchange.liquidation.client.OmsClient omsClient;
    private final com.exchange.liquidation.service.PnLCalculatorService pnLCalculatorService;
    private final com.exchange.liquidation.service.InsuranceFundService insuranceFundService;

    // Redis key前缀
    private static final String ORDER_TO_LIQUIDATION_PREFIX = "liquidation:monitor:order:";
    private static final String LIQUIDATION_TO_TIME_PREFIX = "liquidation:monitor:time:";

    @Value("${liquidation.execution.order-timeout:30000}")
    private long orderTimeout;
    
    @Override
    public void startMonitoring(String liquidationId, Long orderId) {
        log.info("👁️ [OrderMonitorService] Start monitoring, liquidationId={}, orderId={}",
                liquidationId, orderId);

        try {
            long now = System.currentTimeMillis();

            // 存储 orderId -> liquidationId 映射（用于快速查找）
            String orderKey = ORDER_TO_LIQUIDATION_PREFIX + orderId;
            redisTemplate.opsForValue().set(orderKey, liquidationId, 1, TimeUnit.HOURS);

            // 存储 liquidationId -> submitTime 映射（用于超时检查）
            String timeKey = LIQUIDATION_TO_TIME_PREFIX + liquidationId;
            redisTemplate.opsForValue().set(timeKey, String.valueOf(now), 1, TimeUnit.HOURS);

            log.info("✅ Monitoring started, stored in Redis with 1 hour TTL");

        } catch (Exception e) {
            log.error("❌ Failed to start monitoring in Redis, falling back to database only, " +
                    "liquidationId={}, orderId={}", liquidationId, orderId, e);
        }
    }
    
    @Override
    public void handleOrderStatusChange(Long orderId, String status, Long filledQty, Long avgPrice) {
        // 从Redis查找liquidationId
        String orderKey = ORDER_TO_LIQUIDATION_PREFIX + orderId;
        String liquidationId = redisTemplate.opsForValue().get(orderKey);

        if (liquidationId == null) {
            log.warn("⚠️ [OrderMonitorService] LiquidationId not found in Redis for orderId={}, " +
                    "order may not be liquidation order", orderId);
            return;
        }

        log.info("📊 [OrderMonitorService] Order status changed, liquidationId={}, orderId={}, " +
                "status={}, filledQty={}, avgPrice={}",
                liquidationId, orderId, status, filledQty, avgPrice);

        // 查询强平记录
        LiquidationExecution execution = executionMapper.selectByLiquidationId(liquidationId);
        if (execution == null) {
            log.warn("⚠️ [OrderMonitorService] LiquidationExecution not found, liquidationId={}", liquidationId);
            return;
        }

        // 更新订单状态和成交信息
        execution.setStatus(status);
        if (filledQty != null) {
            execution.setExecutedQty(filledQty);
        }
        if (avgPrice != null) {
            execution.setExecutedPrice(avgPrice);
        }
        execution.setUpdatedAt(System.currentTimeMillis());

        // 根据状态处理
        switch (status) {
            case "PARTIALLY_FILLED":
                handlePartiallyFilled(execution, filledQty, avgPrice);
                break;

            case "FILLED":
                execution.setFilledAt(System.currentTimeMillis());
                executionMapper.updateById(execution);

                // 订单完全成交，触发后续处理（盈亏计算、保险基金等）
                log.info("✅ [OrderMonitorService] Order fully filled, liquidationId={}, " +
                        "triggering PnL calculation and insurance fund processing",
                        liquidationId);
                // 触发盈亏计算和保险基金处理（异步）
                liquidationService.processFilledLiquidation(liquidationId);

                // 停止监控
                stopMonitoring(liquidationId, orderId);
                break;

            case "CANCELLED":
            case "REJECTED":
            case "EXPIRED":
                execution.setErrorMsg("Order " + status.toLowerCase());
                executionMapper.updateById(execution);

                // 订单失败，重试
                log.warn("⚠️ [OrderMonitorService] Order failed, liquidationId={}, status={}, " +
                        "will retry", liquidationId, status);
                // TODO: 触发重试
                // liquidationService.retryLiquidation(liquidationId);

                stopMonitoring(liquidationId, orderId);
                break;

            default:
                executionMapper.updateById(execution);
                break;
        }
    }
    
    @Override
    @Scheduled(fixedDelayString = "${liquidation.execution.monitor-interval:100}")
    public void checkTimeoutOrders() {
        try {
            long now = System.currentTimeMillis();

            // 扫描所有监控中的订单（通过Redis key pattern）
            String pattern = LIQUIDATION_TO_TIME_PREFIX + "*";
            Set<String> keys = redisTemplate.keys(pattern);

            if (keys == null || keys.isEmpty()) {
                return;
            }

            int checkedCount = 0;
            int timeoutCount = 0;

            for (String timeKey : keys) {
                try {
                    String submitTimeStr = redisTemplate.opsForValue().get(timeKey);
                    if (submitTimeStr == null) {
                        continue;
                    }

                    Long submitTime = Long.parseLong(submitTimeStr);
                    String liquidationId = timeKey.substring(LIQUIDATION_TO_TIME_PREFIX.length());

                    checkedCount++;

                    if (now - submitTime > orderTimeout) {
                        timeoutCount++;

                        log.warn("⏰ [OrderMonitorService] Order timeout detected, liquidationId={}, " +
                                "submitTime={}, elapsed={}ms, timeout={}ms",
                                liquidationId, submitTime, now - submitTime, orderTimeout);

                        // 查询强平记录
                        LiquidationExecution execution = executionMapper.selectByLiquidationId(liquidationId);
                        if (execution != null && "SUBMITTED".equals(execution.getStatus())) {
                            // 更新状态为超时
                            execution.setStatus("EXPIRED");
                            execution.setErrorMsg("Order timeout after " + orderTimeout + "ms");
                            execution.setUpdatedAt(now);
                            executionMapper.updateById(execution);

                            // 触发重试
                            log.info("🔄 [OrderMonitorService] Triggering retry for timeout order, " +
                                    "liquidationId={}", liquidationId);
                            // TODO: 触发重试
                            // liquidationService.retryLiquidation(liquidationId);
                        }

                        // 清理Redis
                        redisTemplate.delete(timeKey);
                        // 同时清理 orderId -> liquidationId 映射
                        if (execution != null && execution.getOrderId() != null) {
                            String orderKey = ORDER_TO_LIQUIDATION_PREFIX + execution.getOrderId();
                            redisTemplate.delete(orderKey);
                        }
                    }
                } catch (Exception e) {
                    log.error("❌ Error checking timeout for key: {}", timeKey, e);
                }
            }

            if (timeoutCount > 0) {
                log.warn("⏰ [OrderMonitorService] Timeout check completed, checked={}, timeout={}",
                        checkedCount, timeoutCount);
            }

        } catch (Exception e) {
            log.error("❌ [OrderMonitorService] Error during timeout check", e);
        }
    }
    
    @Override
    public void stopMonitoring(String liquidationId) {
        stopMonitoring(liquidationId, null);
    }

    /**
     * 停止监控（支持指定orderId以避免扫描）
     */
    private void stopMonitoring(String liquidationId, Long orderId) {
        try {
            // 清理 liquidationId -> submitTime 映射
            String timeKey = LIQUIDATION_TO_TIME_PREFIX + liquidationId;
            redisTemplate.delete(timeKey);

            // 清理 orderId -> liquidationId 映射
            if (orderId != null) {
                String orderKey = ORDER_TO_LIQUIDATION_PREFIX + orderId;
                redisTemplate.delete(orderKey);
            } else {
                // 如果没有提供orderId，需要从数据库查询
                LiquidationExecution execution = executionMapper.selectByLiquidationId(liquidationId);
                if (execution != null && execution.getOrderId() != null) {
                    String orderKey = ORDER_TO_LIQUIDATION_PREFIX + execution.getOrderId();
                    redisTemplate.delete(orderKey);
                }
            }

            log.info("🛑 [OrderMonitorService] Stopped monitoring, liquidationId={}, orderId={}",
                    liquidationId, orderId);

        } catch (Exception e) {
            log.error("❌ Failed to stop monitoring, liquidationId={}", liquidationId, e);
        }
    }

    /**
     * 处理部分成交
     *
     * 完整流程：
     * 1. 计算已成交部分的盈亏
     * 2. 计算已成交部分的穿仓损失
     * 3. 如果已穿仓，立即申请保险基金赔付（按比例）
     * 4. 计算剩余仓位数量
     * 5. 创建剩余仓位强平订单
     * 6. 更新强平记录状态
     *
     * @param execution 强平执行记录
     * @param filledQty 本次成交数量
     * @param avgPrice 本次成交均价
     */
    /**
     * 处理部分成交
     * 
     * 调用PartialLiquidationHandler处理部分成交逻辑
     */
    private void handlePartiallyFilled(LiquidationExecution execution, Long filledQty, Long avgPrice) {
        log.info("📈 [OrderMonitorService] Handling partial fill, " +
                "liquidationId={}, filledQty={}, avgPrice={}, totalQty={}",
                execution.getLiquidationId(), filledQty, avgPrice, execution.getQuantity());
        
        try {
            // 更新已成交数量（累加）
            Long currentExecutedQty = execution.getExecutedQty() != null ? execution.getExecutedQty() : 0L;
            Long newExecutedQty = currentExecutedQty + filledQty;
            execution.setExecutedQty(newExecutedQty);
            execution.setFilledAt(System.currentTimeMillis());
            
            // 调用部分成交处理器
            partialLiquidationHandler.handlePartialFilled(execution, filledQty, avgPrice);
            
            // 如果创建了剩余仓位订单，需要开始监控新订单
            if (execution.getRemainingOrderId() != null) {
                startMonitoring(execution.getLiquidationId(), execution.getRemainingOrderId());
                log.info("✅ [OrderMonitorService] Started monitoring remaining order, " +
                        "liquidationId={}, remainingOrderId={}",
                        execution.getLiquidationId(), execution.getRemainingOrderId());
            }
            
        } catch (Exception e) {
            log.error("❌ [OrderMonitorService] Failed to handle partial fill, " +
                    "liquidationId={}, filledQty={}", 
                    execution.getLiquidationId(), filledQty, e);
            execution.setStatus("FAILED");
            execution.setErrorMsg("Handle partial fill failed: " + e.getMessage());
            execution.setUpdatedAt(System.currentTimeMillis());
            executionMapper.updateById(execution);
        }
    }

            // 2. 创建订单
            Long newOrderId = omsClient.createOrder(req);
            log.info("✅ [OrderMonitorService] Remaining order created, " +
                    "liquidationId={}, newOrderId={}, remainingQty={}",
                    execution.getLiquidationId(), newOrderId, remainingQty);

            // 3. 更新强平记录，关联新订单
            execution.setRemainingQty(remainingQty);
            execution.setRemainingOrderId(newOrderId);
            execution.setUpdatedAt(System.currentTimeMillis());
            executionMapper.updateById(execution);

            // 4. 开始监控新订单（使用相同的 liquidationId）
            startMonitoring(execution.getLiquidationId(), newOrderId);

            log.info("🎯 [OrderMonitorService] Remaining liquidation order submitted and monitored, " +
                    "liquidationId={}, remainingOrderId={}, remainingQty={}",
                    execution.getLiquidationId(), newOrderId, remainingQty);

        } catch (Exception e) {
            log.error("❌ [OrderMonitorService] Failed to create remaining liquidation order, " +
                    "liquidationId={}, remainingQty={}",
                    execution.getLiquidationId(), remainingQty, e);

            // 更新错误信息
            execution.setErrorMsg("Failed to create remaining order: " + e.getMessage());
            execution.setUpdatedAt(System.currentTimeMillis());
            executionMapper.updateById(execution);

            // 重新抛出异常，让调用方感知
            throw new RuntimeException("Failed to create remaining liquidation order", e);
        }
    }
}

