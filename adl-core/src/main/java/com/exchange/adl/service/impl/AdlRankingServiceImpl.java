package com.exchange.adl.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.exchange.adl.client.PositionServiceClient;
import com.exchange.adl.client.dto.PositionDTO;
import com.exchange.adl.entity.AdlRankingQueue;
import com.exchange.adl.mapper.AdlRankingQueueMapper;
import com.exchange.adl.service.AdlRankingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * ADL排名服务实现
 *
 * 🔥 核心逻辑：
 * 1. 从Position Service获取所有盈利持仓
 * 2. 计算每个持仓的ADL得分
 * 3. 按得分降序排序
 * 4. 更新到adl_ranking_queue表
 * 5. 缓存到Redis（可选）
 */
@Slf4j
@Service
public class AdlRankingServiceImpl implements AdlRankingService {

    @Autowired
    private PositionServiceClient positionServiceClient;

    @Autowired
    private AdlRankingQueueMapper adlRankingQueueMapper;

    // 支持的交易对
    private static final List<String> SUPPORTED_SYMBOLS = Arrays.asList(
            "BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT"
    );

    // 支持的方向
    private static final List<String> SUPPORTED_SIDES = Arrays.asList("LONG", "SHORT");

    @Override
    @Transactional
    public void calculateAndUpdateRanking(String symbol, String side) {
        log.debug("Calculating ADL ranking: symbol={}, side={}", symbol, side);

        long startTime = System.currentTimeMillis();

        try {
            // 1. 从Position Service获取盈利持仓列表
            List<PositionDTO> profitablePositions = positionServiceClient.queryProfitablePositions(
                    symbol, side, 1000 // 最多1000个
            );

            if (profitablePositions.isEmpty()) {
                log.debug("No profitable positions found: symbol={}, side={}", symbol, side);
                // 清空该交易对方向的排名
                clearRanking(symbol, side);
                return;
            }

            // 2. 转换为ADL排名队列实体并计算得分
            List<AdlRankingQueue> rankings = profitablePositions.stream()
                    .map(this::convertToAdlRankingQueue)
                    .filter(AdlRankingQueue::isAdlCandidate) // 过滤有效候选人
                    .collect(Collectors.toList());

            // 3. 按ADL得分降序排序
            rankings.sort(Comparator.comparing(AdlRankingQueue::getAdlScore).reversed()
                    .thenComparing(AdlRankingQueue::getPositionCreatedAt)); // 同分按开仓时间排序

            // 4. 设置排名
            AtomicInteger rank = new AtomicInteger(1);
            rankings.forEach(r -> r.setAdlRank(rank.getAndIncrement()));

            // 5. 批量更新到数据库
            updateRankingBatch(symbol, side, rankings);

            long elapsedTime = System.currentTimeMillis() - startTime;
            log.info("ADL ranking calculated: symbol={}, side={}, count={}, elapsed={}ms",
                    symbol, side, rankings.size(), elapsedTime);

        } catch (Exception e) {
            log.error("Failed to calculate ADL ranking: symbol={}, side={}", symbol, side, e);
        }
    }

    @Override
    public void calculateAllRankings() {
        log.info("Calculating all ADL rankings...");

        long startTime = System.currentTimeMillis();
        int totalCount = 0;

        for (String symbol : SUPPORTED_SYMBOLS) {
            for (String side : SUPPORTED_SIDES) {
                try {
                    calculateAndUpdateRanking(symbol, side);
                    totalCount++;
                } catch (Exception e) {
                    log.error("Failed to calculate ranking: symbol={}, side={}", symbol, side, e);
                }
            }
        }

        long elapsedTime = System.currentTimeMillis() - startTime;
        log.info("All ADL rankings calculated: count={}, elapsed={}ms", totalCount, elapsedTime);
    }

    @Override
    public List<AdlRankingQueue> getRankingList(String symbol, String side, int limit) {
        LambdaQueryWrapper<AdlRankingQueue> query = new LambdaQueryWrapper<>();
        query.eq(AdlRankingQueue::getSymbol, symbol)
             .eq(AdlRankingQueue::getSide, side)
             .orderByAsc(AdlRankingQueue::getAdlRank)
             .last("LIMIT " + limit);

        return adlRankingQueueMapper.selectList(query);
    }

    @Override
    public AdlRankingQueue getUserRanking(Long userId, String symbol) {
        LambdaQueryWrapper<AdlRankingQueue> query = new LambdaQueryWrapper<>();
        query.eq(AdlRankingQueue::getUserId, userId)
             .eq(AdlRankingQueue::getSymbol, symbol)
             .orderByDesc(AdlRankingQueue::getRankUpdatedAt)
             .last("LIMIT 1");

        return adlRankingQueueMapper.selectOne(query);
    }

    @Override
    @Transactional
    public void clearExpiredRankings(long expireTime) {
        LambdaQueryWrapper<AdlRankingQueue> query = new LambdaQueryWrapper<>();
        query.lt(AdlRankingQueue::getRankUpdatedAt, expireTime);

        int count = adlRankingQueueMapper.delete(query);
        if (count > 0) {
            log.info("Cleared expired rankings: count={}", count);
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 转换为ADL排名队列实体
     */
    private AdlRankingQueue convertToAdlRankingQueue(PositionDTO position) {
        AdlRankingQueue queue = new AdlRankingQueue();

        // 基本信息
        queue.setUserId(position.getUserId());
        queue.setSymbol(position.getSymbol());
        queue.setPositionId(position.getPositionId());
        queue.setSide(position.getSide());

        // 持仓信息
        queue.setPositionSize(position.getPositionSize());
        queue.setEntryPrice(position.getEntryPrice());
        queue.setMarkPrice(position.getMarkPrice());

        // 保证金信息
        queue.setMarginBalance(position.getMarginBalance());
        queue.setMaintenanceMargin(position.getMaintenanceMargin());
        queue.setEffectiveLeverage(position.getEffectiveLeverage());

        // 盈亏信息
        queue.setUnrealizedPnl(position.getUnrealizedPnl());
        queue.setPnlRatio(position.getPnlRatio());

        // 计算ADL得分
        queue.calculateAdlScore();

        // 时间戳
        queue.setPositionCreatedAt(position.getCreatedAt());
        queue.setRankUpdatedAt(System.currentTimeMillis());
        queue.setCreatedAt(System.currentTimeMillis());
        queue.setUpdatedAt(System.currentTimeMillis());

        return queue;
    }

    /**
     * 批量更新排名
     */
    @Transactional
    public void updateRankingBatch(String symbol, String side, List<AdlRankingQueue> rankings) {
        // 先删除旧排名
        clearRanking(symbol, side);

        // 批量插入新排名
        if (!rankings.isEmpty()) {
            // 分批插入，每批500条
            int batchSize = 500;
            for (int i = 0; i < rankings.size(); i += batchSize) {
                int end = Math.min(i + batchSize, rankings.size());
                List<AdlRankingQueue> batch = rankings.subList(i, end);

                batch.forEach(adlRankingQueueMapper::insert);

                log.debug("Inserted ADL ranking batch: symbol={}, side={}, batch={}/{}",
                        symbol, side, (i / batchSize) + 1, (rankings.size() + batchSize - 1) / batchSize);
            }
        }
    }

    /**
     * 清空排名
     */
    private void clearRanking(String symbol, String side) {
        LambdaQueryWrapper<AdlRankingQueue> query = new LambdaQueryWrapper<>();
        query.eq(AdlRankingQueue::getSymbol, symbol)
             .eq(AdlRankingQueue::getSide, side);

        adlRankingQueueMapper.delete(query);
    }
}
