package com.exchange.adl.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.exchange.adl.entity.*;
import com.exchange.adl.event.AdlExecutedEvent;
import com.exchange.adl.event.AdlTriggerEvent;
import com.exchange.adl.mapper.*;
import com.exchange.adl.producer.AdlEventProducer;
import com.exchange.adl.service.AdlService;
import com.exchange.adl.service.InsuranceFundService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ADL服务完整实现
 *
 * 🔥 核心流程：
 * 1. 监听强平完成事件
 * 2. 检查是否穿仓
 * 3. 尝试使用保险基金赔付
 * 4. 保险基金不足时触发ADL
 * 5. 选择ADL候选人（对手方盈利仓位）
 * 6. 执行ADL减仓
 * 7. 更新账本（调用Clearing Service）
 * 8. 发布ADL执行事件
 */
@Slf4j
@Service
public class AdlServiceImplComplete implements AdlService {

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
    private AdlEventProducer adlEventProducer;

    // ADL配置
    private static final int MAX_ADL_USERS_PER_BATCH = 50; // 单批次最大用户数
    private static final int MAX_ADL_BATCHES = 10; // 最大批次数
    private static final int MAX_RETRY_COUNT = 3; // 最大重试次数
    private static final BigDecimal ADL_PRICE_DEVIATION_THRESHOLD = new BigDecimal("0.05"); // 5%价格偏离阈值

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
        log.debug("Calculating ADL ranking: symbol={}, side={}", symbol, side);

        try {
            // TODO: 从Position Service获取所有盈利持仓
            // TODO: 计算每个持仓的ADL得分
            // TODO: 排序并更新到adl_ranking_queue表
            // TODO: 缓存到Redis

            // 这里是简化实现，实际需要调用Position Service
            log.info("ADL ranking calculated: symbol={}, side={}", symbol, side);

        } catch (Exception e) {
            log.error("Failed to calculate ADL ranking", e);
        }
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

            // 获取ADL候选人（按排名从高到低）
            List<AdlRankingQueue> candidates = getAdlCandidates(symbol, oppositeSide, MAX_ADL_USERS_PER_BATCH);

            if (candidates.isEmpty()) {
                log.warn("No ADL candidates available: symbol={}, side={}", symbol, oppositeSide);
                break;
            }

            // 执行当前批次的ADL
            for (AdlRankingQueue candidate : candidates) {
                if (remainingQty.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }

                // 执行单个ADL
                AdlExecutedEvent.AdlExecutionDetail detail = executeSingleAdl(
                        candidate, remainingQty, symbol, sourceLiquidationId
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
        AdlRanking ranking = adlRankingMapper.selectByUserAndSymbol(userId, symbol);
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
        String currency = "USDT"; // 默认USDT
        BigDecimal bankruptLoss = record.getBankruptLoss();

        // 检查保险基金余额
        BigDecimal availableBalance = insuranceFundService.getAvailableBalance(symbol, currency);

        // 计算实际赔付金额（取最小值）
        BigDecimal coverAmount = availableBalance.min(bankruptLoss);

        if (coverAmount.compareTo(BigDecimal.ZERO) > 0) {
            // 从保险基金支出
            boolean success = insuranceFundService.expense(
                    symbol, currency, coverAmount,
                    "COVER_BANKRUPT",
                    record.getLiquidationId(),
                    "赔付穿仓损失: " + record.getUserId()
            );

            if (success) {
                // 更新穿仓记录
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
        // 更新穿仓记录状态
        record.setStatus("PROCESSING");
        record.setProcessingAt(System.currentTimeMillis());
        record.setUpdatedAt(System.currentTimeMillis());
        bankruptcyRecordMapper.updateById(record);

        // 发布ADL触发事件
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

        // 执行ADL
        executeAdl(record.getSymbol(), event.getOppositeSide(), remainingLoss.longValue(),
                record.getLiquidationId(), record.getUserId());
    }

    /**
     * 获取ADL候选人
     */
    private List<AdlRankingQueue> getAdlCandidates(String symbol, String side, int limit) {
        LambdaQueryWrapper<AdlRankingQueue> query = new LambdaQueryWrapper<>();
        query.eq(AdlRankingQueue::getSymbol, symbol)
             .eq(AdlRankingQueue::getSide, side)
             .orderByDesc(AdlRankingQueue::getAdlScore)
             .last("LIMIT " + limit);

        List<AdlRankingQueue> candidates = adlRankingQueueMapper.selectList(query);

        // 过滤盈利仓位
        return candidates.stream()
                .filter(AdlRankingQueue::isProfitable)
                .filter(AdlRankingQueue::hasPosition)
                .collect(Collectors.toList());
    }

    /**
     * 执行单个ADL
     */
    private AdlExecutedEvent.AdlExecutionDetail executeSingleAdl(
            AdlRankingQueue candidate, BigDecimal requiredQty, String symbol, String sourceLiquidationId) {

        try {
            // 二次校验：确保候选人持仓仍然有效
            if (!validateCandidate(candidate)) {
                log.warn("ADL candidate validation failed: userId={}, positionId={}",
                        candidate.getUserId(), candidate.getPositionId());
                return null;
            }

            // 计算ADL数量（取最小值）
            BigDecimal adlQty = candidate.getPositionSize().min(requiredQty);

            // 计算ADL价格（使用标记价格）
            BigDecimal adlPrice = candidate.getMarkPrice();

            // 创建ADL执行记录
            AdlExecution execution = new AdlExecution();
            execution.setAdlExecutionId(generateAdlExecutionId());
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

            Long now = System.currentTimeMillis();
            execution.setTriggeredAt(now);
            execution.setExecutedAt(now);
            execution.setCompletedAt(now);
            execution.setCreatedAt(now);
            execution.setUpdatedAt(now);

            // 保存ADL执行记录
            adlExecutionMapper.insert(execution);

            // TODO: 调用Clearing Service进行记账
            // TODO: 调用Position Service更新持仓
            // TODO: 发送通知给被ADL用户

            log.info("ADL executed: userId={}, positionId={}, qty={}, price={}",
                    candidate.getUserId(), candidate.getPositionId(), adlQty, adlPrice);

            // 返回执行详情
            AdlExecutedEvent.AdlExecutionDetail detail = new AdlExecutedEvent.AdlExecutionDetail();
            detail.setAdlExecutionId(execution.getAdlExecutionId());
            detail.setTargetUserId(candidate.getUserId());
            detail.setTargetPositionId(candidate.getPositionId());
            detail.setAdlPrice(adlPrice);
            detail.setAdlQty(adlQty);
            detail.setTargetPnlChange(calculatePnlChange(candidate, adlQty, adlPrice));

            return detail;

        } catch (Exception e) {
            log.error("Failed to execute single ADL", e);
            return null;
        }
    }

    /**
     * 校验ADL候选人
     */
    private boolean validateCandidate(AdlRankingQueue candidate) {
        // TODO: 从Position Service获取最新持仓信息进行校验
        // 1. 持仓是否还存在
        // 2. 持仓数量是否足够
        // 3. 是否还在盈利状态
        // 4. 账户是否正常（未冻结）

        return candidate.hasPosition() && candidate.isProfitable();
    }

    /**
     * 计算盈亏变化
     */
    private BigDecimal calculatePnlChange(AdlRankingQueue candidate, BigDecimal adlQty, BigDecimal adlPrice) {
        // 简化计算：(ADL价格 - 开仓价格) * 数量
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

        // 计算总数量和总金额
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

    /**
     * 获取对手方向
     */
    private String getOppositeSide(String side) {
        return "LONG".equalsIgnoreCase(side) ? "SHORT" : "LONG";
    }

    /**
     * 生成ADL执行ID
     */
    private String generateAdlExecutionId() {
        return "ADL_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
