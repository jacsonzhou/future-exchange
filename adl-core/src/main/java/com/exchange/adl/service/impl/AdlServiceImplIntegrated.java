package com.exchange.adl.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.exchange.adl.client.ClearingServiceClient;
import com.exchange.adl.client.PositionServiceClient;
import com.exchange.adl.client.dto.ClearingResponse;
import com.exchange.adl.entity.*;
import com.exchange.adl.event.AdlExecutedEvent;
import com.exchange.adl.event.AdlTriggerEvent;
import com.exchange.adl.mapper.*;
import com.exchange.adl.producer.AdlEventProducer;
import com.exchange.adl.service.AdlRankingService;
import com.exchange.adl.service.AdlService;
import com.exchange.adl.service.InsuranceFundService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ADL服务完整实现（集成版）
 *
 * 🔥 核心流程：
 * 1. 监听强平完成事件
 * 2. 检查是否穿仓
 * 3. 尝试使用保险基金赔付
 * 4. 保险基金不足时触发ADL
 * 5. 从ADL排名队列选择候选人
 * 6. 执行ADL减仓
 * 7. 调用Clearing Service记账
 * 8. 通知Position Service更新持仓
 * 9. 发布ADL执行事件
 */
@Slf4j
@Service
@Primary
public class AdlServiceImplIntegrated implements AdlService {

    @Autowired
    private AdlRankingMapper adlRankingMapper;

    @Autowired
    private AdlRankingQueueMapper adlRankingQueueMapper;

    @Autowired
    private AdlExecutionMapper adlExecutionMapper;

    @Autowired
    private BankruptcyRecordMapper bankruptcyRecordMapper;

    @Autowired
    private InsuranceFundService insuranceFundService;

    @Autowired
    private AdlRankingService adlRankingService;

    @Autowired
    private PositionServiceClient positionServiceClient;

    @Autowired
    private ClearingServiceClient clearingServiceClient;

    @Autowired
    private AdlEventProducer adlEventProducer;

    // ADL配置
    private static final int MAX_ADL_USERS_PER_BATCH = 50;
    private static final int MAX_ADL_BATCHES = 10;
    private static final int MAX_RETRY_COUNT = 3;

    @Override
    @Transactional
    public void onLiquidationCompleted(String liquidationId, Long userId, String symbol, String side,
                                        Long bankruptPrice, Long bankruptQty, Long bankruptLoss) {
        log.info("Processing liquidation event: liquidationId={}, symbol={}, userId={}, loss={}",
                liquidationId, symbol, userId, bankruptLoss);

        // 1. 创建穿仓记录
        BankruptcyRecord record = createBankruptcyRecord(
                liquidationId, userId, symbol, side,
                new BigDecimal(bankruptPrice), new BigDecimal(bankruptQty), new BigDecimal(bankruptLoss)
        );

        // 2. 尝试使用保险基金赔付
        BigDecimal insuranceCover = tryInsuranceFundCover(record);

        // 3. 计算剩余需要ADL的金额
        BigDecimal remainingLoss = new BigDecimal(bankruptLoss).subtract(insuranceCover);

        if (remainingLoss.compareTo(BigDecimal.ZERO) > 0) {
            // 4. 触发ADL
            log.warn("Insurance fund insufficient, triggering ADL: symbol={}, remainingLoss={}",
                    symbol, remainingLoss);

            triggerAdl(record, remainingLoss, side);
        } else {
            // 保险基金足够，标记完成
            record.setStatus("COMPLETED");
            record.setCompletedAt(System.currentTimeMillis());
            bankruptcyRecordMapper.updateById(record);

            log.info("Bankruptcy covered by insurance fund: recordId={}, amount={}",
                    record.getId(), insuranceCover);
        }
    }

    @Override
    public void calculateAdlRanking(String symbol, String side) {
        // 委托给AdlRankingService
        adlRankingService.calculateAndUpdateRanking(symbol, side);
    }

    @Override
    public List<AdlRanking> getAdlRankings(String symbol, String side, int limit) {
        return adlRankingMapper.selectBySymbolAndSide(symbol, side, limit);
    }

    @Override
    public AdlRanking getUserAdlRank(Long userId, String symbol) {
        return adlRankingMapper.selectByUserAndSymbol(userId, symbol);
    }

    @Override
    @Transactional
    public void executeAdl(String symbol, String oppositeSide, Long requiredQty, String sourceLiquidationId, Long sourceUserId) {
        log.info("Executing ADL: symbol={}, oppositeSide={}, requiredQty={}, sourceLiquidationId={}",
                symbol, oppositeSide, requiredQty, sourceLiquidationId);

        BigDecimal remainingQty = new BigDecimal(requiredQty);
        int batchCount = 0;
        int affectedUsers = 0;
        List<AdlExecutedEvent.AdlExecutionDetail> executionDetails = new ArrayList<>();

        while (remainingQty.compareTo(BigDecimal.ZERO) > 0 && batchCount < MAX_ADL_BATCHES) {
            batchCount++;

            // 获取ADL候选人（从排名队列）
            List<AdlRankingQueue> candidates = getAdlCandidatesFromQueue(symbol, oppositeSide, MAX_ADL_USERS_PER_BATCH);

            if (candidates.isEmpty()) {
                log.warn("No ADL candidates available: symbol={}, side={}", symbol, oppositeSide);
                break;
            }

            // 执行当前批次的ADL
            for (AdlRankingQueue candidate : candidates) {
                if (remainingQty.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }

                // 执行单个ADL（包含Clearing集成）
                AdlExecutedEvent.AdlExecutionDetail detail = executeSingleAdlWithClearing(
                        candidate, remainingQty, symbol, sourceLiquidationId, sourceUserId
                );

                if (detail != null) {
                    executionDetails.add(detail);
                    remainingQty = remainingQty.subtract(detail.getAdlQty());
                    affectedUsers++;
                }
            }

            log.info("ADL batch {} completed: affectedUsers={}, remainingQty={}",
                    batchCount, affectedUsers, remainingQty);
        }

        // 发布ADL执行完成事件
        publishAdlExecutedEvent(symbol, sourceLiquidationId, executionDetails, affectedUsers);

        log.info("ADL execution completed: symbol={}, batches={}, affectedUsers={}, remainingQty={}",
                symbol, batchCount, affectedUsers, remainingQty);
    }

    @Override
    public Long getInsuranceFundBalance(String symbol, String currency) {
        BigDecimal balance = insuranceFundService.getBalance(symbol, currency);
        return balance.longValue();
    }

    @Override
    public boolean isInAdlZone(Long userId, String symbol) {
        AdlRankingQueue ranking = adlRankingService.getUserRanking(userId, symbol);
        if (ranking == null) {
            return false;
        }
        // 排名在前20%认为在ADL危险区
        return ranking.getAdlRank() <= 20;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 创建穿仓记录
     */
    private BankruptcyRecord createBankruptcyRecord(String liquidationId, Long userId, String symbol,
                                                    String side, BigDecimal bankruptPrice,
                                                    BigDecimal bankruptQty, BigDecimal bankruptLoss) {
        BankruptcyRecord record = new BankruptcyRecord();
        record.setLiquidationId(liquidationId);
        record.setUserId(userId);
        record.setSymbol(symbol);
        record.setSide(side);
        record.setBankruptPrice(bankruptPrice);
        record.setBankruptQty(bankruptQty);
        record.setBankruptLoss(bankruptLoss);
        record.setStatus("PENDING");
        record.setHandleType("PENDING");
        record.setRetryCount(0);

        Long now = System.currentTimeMillis();
        record.setBankruptAt(now);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);

        bankruptcyRecordMapper.insert(record);

        log.info("Bankruptcy record created: id={}, liquidationId={}, loss={}",
                record.getId(), liquidationId, bankruptLoss);

        return record;
    }

    /**
     * 尝试使用保险基金赔付
     */
    private BigDecimal tryInsuranceFundCover(BankruptcyRecord record) {
        String symbol = record.getSymbol();
        String currency = "USDT";
        BigDecimal bankruptLoss = record.getBankruptLoss();

        BigDecimal availableBalance = insuranceFundService.getAvailableBalance(symbol, currency);
        BigDecimal coverAmount = availableBalance.min(bankruptLoss);

        if (coverAmount.compareTo(BigDecimal.ZERO) > 0) {
            boolean success = insuranceFundService.expense(
                    symbol, currency, coverAmount,
                    "COVER_BANKRUPT",
                    record.getLiquidationId(),
                    "赔付穿仓损失: " + record.getUserId()
            );

            if (success) {
                record.setInsuranceCover(coverAmount);
                record.updateProgress(coverAmount, BigDecimal.ZERO);
                record.setUpdatedAt(System.currentTimeMillis());
                bankruptcyRecordMapper.updateById(record);

                log.info("Insurance fund covered: symbol={}, amount={}, remainingLoss={}",
                        symbol, coverAmount, record.getUncoveredAmount());

                return coverAmount;
            }
        }

        log.warn("Insurance fund insufficient: symbol={}, available={}, required={}",
                symbol, availableBalance, bankruptLoss);

        return BigDecimal.ZERO;
    }

    /**
     * 触发ADL
     */
    private void triggerAdl(BankruptcyRecord record, BigDecimal remainingLoss, String bankruptSide) {
        record.setStatus("PROCESSING");
        record.setProcessingAt(System.currentTimeMillis());
        record.setUpdatedAt(System.currentTimeMillis());
        bankruptcyRecordMapper.updateById(record);

        AdlTriggerEvent event = new AdlTriggerEvent();
        event.setAdlTriggerId(UUID.randomUUID().toString());
        event.setBankruptcyRecordId(String.valueOf(record.getId()));
        event.setLiquidationId(record.getLiquidationId());
        event.setSymbol(record.getSymbol());
        event.setOppositeSide(getOppositeSide(bankruptSide));
        event.setRequiredAmount(remainingLoss);
        event.setReason("保险基金不足");
        event.setInsuranceFundBalance(insuranceFundService.getBalance(record.getSymbol(), "USDT"));
        event.setTriggeredAt(System.currentTimeMillis());
        event.setTimestamp(System.currentTimeMillis());

        adlEventProducer.publishAdlTrigger(event);

        executeAdl(record.getSymbol(), event.getOppositeSide(), remainingLoss.longValue(),
                record.getLiquidationId(), record.getUserId());
    }

    /**
     * 从排名队列获取ADL候选人
     */
    private List<AdlRankingQueue> getAdlCandidatesFromQueue(String symbol, String side, int limit) {
        return adlRankingService.getRankingList(symbol, side, limit);
    }

    /**
     * 执行单个ADL（包含Clearing集成）
     */
    private AdlExecutedEvent.AdlExecutionDetail executeSingleAdlWithClearing(
            AdlRankingQueue candidate,
            BigDecimal requiredQty,
            String symbol,
            String sourceLiquidationId,
            Long sourceUserId) {

        try {
            // 1. 二次校验：确保候选人持仓仍然有效
            if (!positionServiceClient.validatePosition(
                    candidate.getUserId(), candidate.getPositionId(), candidate.getSide())) {
                log.warn("ADL candidate validation failed: userId={}, positionId={}",
                        candidate.getUserId(), candidate.getPositionId());
                return null;
            }

            // 2. 计算ADL数量和价格
            BigDecimal adlQty = candidate.getPositionSize().min(requiredQty);
            BigDecimal adlPrice = candidate.getMarkPrice();

            // 3. 生成ADL执行ID
            String adlExecutionId = generateAdlExecutionId();

            // 4. 调用Clearing Service进行记账
            BigDecimal targetPnlChange = calculatePnlChange(candidate, adlQty, adlPrice);

            ClearingResponse clearingResponse = clearingServiceClient.submitAdlClearing(
                    adlExecutionId,
                    candidate.getUserId(),
                    candidate.getPositionId(),
                    sourceUserId != null ? sourceUserId : 0L,
                    symbol,
                    candidate.getSide(),
                    adlPrice,
                    adlQty,
                    targetPnlChange
            );

            if (!clearingResponse.getSuccess()) {
                log.error("Clearing failed for ADL: adlExecutionId={}, message={}",
                        adlExecutionId, clearingResponse.getMessage());
                return null;
            }

            // 5. 创建ADL执行记录
            AdlExecution execution = new AdlExecution();
            execution.setAdlExecutionId(adlExecutionId);
            execution.setLiquidationId(sourceLiquidationId);
            execution.setSymbol(symbol);
            execution.setTargetUserId(candidate.getUserId());
            execution.setTargetPositionId(candidate.getPositionId());
            execution.setTargetSide(candidate.getSide());
            execution.setTargetPositionSizeBefore(candidate.getPositionSize());
            execution.setTargetPositionSizeAfter(candidate.getPositionSize().subtract(adlQty));
            execution.setTargetAdlRank(candidate.getAdlRank());
            execution.setTargetAdlScore(candidate.getAdlScore());
            execution.setAdlPrice(adlPrice);
            execution.setAdlQty(adlQty);
            execution.calculateAdlValue();
            execution.setIsFullyClosed(execution.getTargetPositionSizeAfter().compareTo(BigDecimal.ZERO) == 0);
            execution.setStatus("SUCCESS");
            execution.setBizSeq(adlExecutionId);

            Long now = System.currentTimeMillis();
            execution.setTriggeredAt(now);
            execution.setExecutedAt(now);
            execution.setCompletedAt(now);
            execution.setCreatedAt(now);
            execution.setUpdatedAt(now);

            adlExecutionMapper.insert(execution);

            // 6. 通知Position Service（可选，实际持仓更新由Clearing事件驱动）
            positionServiceClient.notifyAdlExecution(
                    candidate.getUserId(),
                    candidate.getPositionId(),
                    adlExecutionId
            );

            log.info("ADL executed successfully: userId={}, positionId={}, qty={}, price={}",
                    candidate.getUserId(), candidate.getPositionId(), adlQty, adlPrice);

            // 7. 返回执行详情
            AdlExecutedEvent.AdlExecutionDetail detail = new AdlExecutedEvent.AdlExecutionDetail();
            detail.setAdlExecutionId(adlExecutionId);
            detail.setTargetUserId(candidate.getUserId());
            detail.setTargetPositionId(candidate.getPositionId());
            detail.setAdlPrice(adlPrice);
            detail.setAdlQty(adlQty);
            detail.setTargetPnlChange(targetPnlChange);

            return detail;

        } catch (Exception e) {
            log.error("Failed to execute single ADL", e);
            return null;
        }
    }

    /**
     * 计算盈亏变化
     */
    private BigDecimal calculatePnlChange(AdlRankingQueue candidate, BigDecimal adlQty, BigDecimal adlPrice) {
        BigDecimal priceDiff = adlPrice.subtract(candidate.getEntryPrice());
        if (candidate.isShort()) {
            priceDiff = priceDiff.negate();
        }
        return priceDiff.multiply(adlQty);
    }

    /**
     * 发布ADL执行完成事件
     */
    private void publishAdlExecutedEvent(String symbol, String liquidationId,
                                         List<AdlExecutedEvent.AdlExecutionDetail> executionDetails,
                                         int affectedUsers) {
        AdlExecutedEvent event = new AdlExecutedEvent();
        event.setAdlBatchId(UUID.randomUUID().toString());
        event.setBankruptcyRecordId(liquidationId);
        event.setSymbol(symbol);
        event.setExecutions(executionDetails);
        event.setAffectedUsers(affectedUsers);

        BigDecimal totalQty = executionDetails.stream()
                .map(AdlExecutedEvent.AdlExecutionDetail::getAdlQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAmount = executionDetails.stream()
                .map(detail -> detail.getAdlQty().multiply(detail.getAdlPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        event.setTotalQty(totalQty);
        event.setTotalAmount(totalAmount);
        event.setStatus("COMPLETED");
        event.setExecutedAt(System.currentTimeMillis());
        event.setTimestamp(System.currentTimeMillis());

        adlEventProducer.publishAdlExecuted(event);
    }

    private String getOppositeSide(String side) {
        return "LONG".equalsIgnoreCase(side) ? "SHORT" : "LONG";
    }

    private String generateAdlExecutionId() {
        return "ADL_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
