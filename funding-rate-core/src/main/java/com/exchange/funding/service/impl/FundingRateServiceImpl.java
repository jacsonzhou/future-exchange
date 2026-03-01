package com.exchange.funding.service.impl;

import com.exchange.common.core.Money;
import com.exchange.common.core.Result;
import com.exchange.funding.client.*;
import com.exchange.funding.dto.IndexPriceDTO;
import com.exchange.funding.dto.MarkPriceDTO;
import com.exchange.funding.dto.PositionDTO;
import com.exchange.funding.entity.FundingRateConfig;
import com.exchange.funding.entity.FundingRateEstimate;
import com.exchange.funding.entity.FundingRateHistory;
import com.exchange.funding.entity.UserFundingFee;
import com.exchange.funding.event.FundingRateCalcEvent;
import com.exchange.funding.event.FundingSettlementEvent;
import com.exchange.funding.event.UserFundingFeeEvent;
import com.exchange.funding.mapper.FundingRateConfigMapper;
import com.exchange.funding.mapper.FundingRateEstimateMapper;
import com.exchange.funding.mapper.FundingRateHistoryMapper;
import com.exchange.funding.mapper.UserFundingFeeMapper;
import com.exchange.funding.producer.FundingEventProducer;
import com.exchange.funding.service.FundingRateService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 资金费率服务实现
 */
@Slf4j
@Service
public class FundingRateServiceImpl implements FundingRateService {

    @Autowired
    private FundingRateConfigMapper configMapper;

    @Autowired
    private FundingRateHistoryMapper historyMapper;

    @Autowired
    private UserFundingFeeMapper userFundingFeeMapper;

    @Autowired
    private FundingRateEstimateMapper estimateMapper;

    @Autowired
    private IndexPriceClient indexPriceClient;

    @Autowired
    private MarkPriceClient markPriceClient;

    @Autowired
    private PositionClient positionClient;

    @Autowired
    private LedgerClient ledgerClient;

    @Autowired
    private FundingEventProducer eventProducer;

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    // 精度: 0.01% = 10000
    private static final long RATE_PRECISION = 10000L;
    // 默认结算间隔: 8小时 = 28800秒
    private static final long DEFAULT_INTERVAL = 28800L;
    // Redis缓存键前缀
    private static final String CACHE_PREFIX_INDEX = "funding:index:";
    private static final String CACHE_PREFIX_MARK = "funding:mark:";
    private static final String CACHE_PREFIX_ESTIMATE = "funding:estimate:";
    
    @Override
    @Transactional
    public FundingRateHistory calculateFundingRate(String symbol, long fundingTime) {
        log.info("Calculating funding rate for symbol={}, time={}", symbol, fundingTime);

        // 1. 获取配置
        FundingRateConfig config = configMapper.selectBySymbol(symbol);
        if (config == null || config.getStatus() != 1) {
            log.warn("Funding rate config not found or disabled for symbol={}", symbol);
            return null;
        }

        // 2. 获取指数价格和标记价格
        long indexPrice = getIndexPrice(symbol);
        long markPrice = getMarkPrice(symbol);

        if (indexPrice == 0 || markPrice == 0) {
            log.error("Invalid price for symbol={}: indexPrice={}, markPrice={}", symbol, indexPrice, markPrice);
            throw new RuntimeException("Invalid price data");
        }

        // 3. 获取持仓统计
        PositionStatsDTO positionStats = getPositionStats(symbol);

        // 4. 计算溢价指数
        long premiumIndex = calculatePremiumIndex(markPrice, indexPrice);

        // 5. 计算资金费率 = 溢价指数 + 利率差
        long fundingRate = premiumIndex + config.getInterestRate();

        // 6. 限制在最大/最小范围内
        fundingRate = Math.max(config.getMinRate(), Math.min(config.getMaxRate(), fundingRate));

        // 7. 保存历史记录
        FundingRateHistory history = new FundingRateHistory();
        history.setSymbol(symbol);
        history.setFundingTime(fundingTime);
        history.setFundingRate(fundingRate);
        history.setMarkPrice(markPrice);
        history.setIndexPrice(indexPrice);
        history.setPremiumIndex(premiumIndex);
        history.setTotalLongQty(positionStats.getTotalLongQty());
        history.setTotalShortQty(positionStats.getTotalShortQty());

        historyMapper.insert(history);

        // 8. 发布资金费率计算完成事件
        publishFundingRateCalcEvent(history);

        log.info("Funding rate calculated for {}: rate={}%, premium={}%, markPrice={}, indexPrice={}",
                symbol, Money.format(fundingRate), Money.format(premiumIndex), Money.format(markPrice), Money.format(indexPrice));

        return history;
    }
    
    @Override
    @Transactional
    public void settleFundingFee(String symbol, long fundingTime) {
        long startTime = System.currentTimeMillis();
        log.info("Settling funding fee for symbol={}, time={}", symbol, fundingTime);

        // 1. 获取资金费率
        FundingRateHistory history = historyMapper.selectBySymbolAndTime(symbol, fundingTime);
        if (history == null) {
            log.error("Funding rate history not found for symbol={}, time={}", symbol, fundingTime);
            throw new RuntimeException("Funding rate history not found");
        }

        long fundingRate = history.getFundingRate();
        long markPrice = history.getMarkPrice();

        // 2. 获取所有持仓用户
        List<PositionDTO> positions = getAllPositions(symbol);
        if (positions.isEmpty()) {
            log.info("No positions found for symbol={}, skip settlement", symbol);
            return;
        }

        log.info("Found {} positions for symbol={}", positions.size(), symbol);

        // 3. 逐用户计算资金费用并结算
        AtomicInteger totalUsers = new AtomicInteger(0);
        AtomicLong totalAmount = new AtomicLong(0);
        AtomicInteger longPayersCount = new AtomicInteger(0);
        AtomicInteger shortPayersCount = new AtomicInteger(0);

        for (PositionDTO position : positions) {
            try {
                // 跳过零持仓
                if (position.getQty() == null || position.getQty() == 0) {
                    continue;
                }

                // 计算资金费用
                // 资金费用 = 持仓名义价值 × 资金费率
                // 持仓名义价值 = 持仓数量 × 标记价格
                long positionValue = Math.multiplyExact(position.getQty(), markPrice) / Money.SCALE;
                long fundingFee = Math.multiplyExact(positionValue, fundingRate) / RATE_PRECISION;

                // 根据持仓方向调整费用符号
                // 资金费率为正: 多头支付(+)，空头收取(-)
                // 资金费率为负: 空头支付(+)，多头收取(-)
                if ("SHORT".equals(position.getSide())) {
                    fundingFee = -fundingFee;
                }

                // 如果费用为0，跳过
                if (fundingFee == 0) {
                    continue;
                }

                // 保存用户资金费用记录
                UserFundingFee userFee = new UserFundingFee();
                userFee.setUserId(position.getUserId());
                userFee.setSymbol(symbol);
                userFee.setFundingTime(fundingTime);
                userFee.setSide(position.getSide());
                userFee.setPositionQty(position.getQty());
                userFee.setMarkPrice(markPrice);
                userFee.setFundingRate(fundingRate);
                userFee.setFundingFee(fundingFee);
                userFee.setMarginMode(position.getMarginMode());
                userFee.setStatus("SETTLED");

                userFundingFeeMapper.insert(userFee);

                // 调用Ledger服务记账
                createLedgerEntry(position.getUserId(), symbol, fundingTime, position.getSide(), fundingFee, position.getMarginMode());

                // 发布用户资金费用事件
                publishUserFundingFeeEvent(userFee, fundingRate);

                // 统计
                totalUsers.incrementAndGet();
                totalAmount.addAndGet(Math.abs(fundingFee));
                if (fundingFee > 0) {
                    // 支付方
                    if ("LONG".equals(position.getSide())) {
                        longPayersCount.incrementAndGet();
                    } else {
                        shortPayersCount.incrementAndGet();
                    }
                }

                log.debug("Settled funding fee for user={}, symbol={}, side={}, qty={}, fee={}",
                        position.getUserId(), symbol, position.getSide(), position.getQty(), fundingFee);

            } catch (Exception e) {
                log.error("Failed to settle funding fee for user={}, symbol={}", position.getUserId(), symbol, e);
                // 单个用户失败不影响其他用户，继续处理
            }
        }

        // 4. 更新结算总金额到历史记录
        history.setSettlementAmount(totalAmount.get());
        historyMapper.updateById(history);

        // 5. 发布结算完成事件
        long duration = System.currentTimeMillis() - startTime;
        publishFundingSettlementEvent(symbol, fundingTime, totalUsers.get(), totalAmount.get(),
                longPayersCount.get(), shortPayersCount.get(), duration);

        log.info("Funding fee settlement completed for symbol={}: users={}, amount={}, duration={}ms",
                symbol, totalUsers.get(), Money.format(totalAmount.get()), duration);
    }
    
    @Override
    public List<FundingRateHistory> getFundingRateHistory(String symbol, int limit) {
        return historyMapper.selectBySymbolLimit(symbol, limit);
    }
    
    @Override
    public Long getEstimatedFundingRate(String symbol) {
        // 获取当前预估费率 (从缓存或实时计算)
        FundingRateConfig config = configMapper.selectBySymbol(symbol);
        if (config == null) {
            return 0L;
        }
        
        long indexPrice = getIndexPrice(symbol);
        long markPrice = getMarkPrice(symbol);
        long premiumIndex = calculatePremiumIndex(markPrice, indexPrice);
        long estimatedRate = premiumIndex + config.getInterestRate();
        
        return Math.max(config.getMinRate(), Math.min(config.getMaxRate(), estimatedRate));
    }
    
    @Override
    public List<UserFundingFee> getUserFundingFees(Long userId, String symbol, Long startTime, Long endTime) {
        return userFundingFeeMapper.selectByUserAndTime(userId, symbol, startTime, endTime);
    }
    
    @Override
    public Long getNextFundingTime(String symbol) {
        long now = System.currentTimeMillis();
        long interval = DEFAULT_INTERVAL * 1000; // 转换为毫秒

        // 结算时间点: 00:00, 08:00, 16:00 UTC
        long dayStart = (now / (24 * 60 * 60 * 1000)) * (24 * 60 * 60 * 1000);
        long[] fundingTimes = {
            dayStart,                           // 00:00
            dayStart + 8 * 60 * 60 * 1000,      // 08:00
            dayStart + 16 * 60 * 60 * 1000      // 16:00
        };

        for (long fundingTime : fundingTimes) {
            if (fundingTime > now) {
                return fundingTime;
            }
        }

        // 如果今天的时间都过了，返回明天00:00
        return dayStart + 24 * 60 * 60 * 1000;
    }

    @Override
    @Transactional
    public void updateEstimatedRate(String symbol, Long estimatedRate, Long nextFundingTime) {
        try {
            // 获取当前价格
            long indexPrice = getIndexPrice(symbol);
            long markPrice = getMarkPrice(symbol);
            long premiumIndex = calculatePremiumIndex(markPrice, indexPrice);

            // 构建或更新预估费率记录
            FundingRateEstimate estimate = estimateMapper.selectById(symbol);
            if (estimate == null) {
                estimate = new FundingRateEstimate();
                estimate.setSymbol(symbol);
            }

            estimate.setNextFundingTime(nextFundingTime);
            estimate.setEstimatedRate(estimatedRate);
            estimate.setMarkPrice(markPrice);
            estimate.setIndexPrice(indexPrice);
            estimate.setPremiumIndex(premiumIndex);

            if (estimateMapper.selectById(symbol) == null) {
                estimateMapper.insert(estimate);
            } else {
                estimateMapper.updateById(estimate);
            }

            // 更新到Redis缓存
            if (redisTemplate != null) {
                redisTemplate.opsForValue().set(CACHE_PREFIX_ESTIMATE + symbol, estimate, 10, TimeUnit.SECONDS);
            }

        } catch (Exception e) {
            log.error("Failed to update estimated rate for symbol={}", symbol, e);
        }
    }
    
    /**
     * 计算溢价指数
     */
    private long calculatePremiumIndex(long markPrice, long indexPrice) {
        if (indexPrice == 0) {
            return 0;
        }
        // 溢价指数 = (标记价格 - 指数价格) / 指数价格
        return (markPrice - indexPrice) * RATE_PRECISION / indexPrice;
    }

    /**
     * 获取指数价格（带缓存和降级）
     */
    @CircuitBreaker(name = "indexPriceService", fallbackMethod = "getIndexPriceFallback")
    @Retry(name = "indexPriceService")
    private long getIndexPrice(String symbol) {
        try {
            Result<IndexPriceDTO> result = indexPriceClient.getIndexPrice(symbol);
            if (result != null && result.getData() != null) {
                long price = result.getData().getPrice();
                // 缓存到Redis，TTL=60秒
                if (redisTemplate != null) {
                    redisTemplate.opsForValue().set(CACHE_PREFIX_INDEX + symbol, price, 60, TimeUnit.SECONDS);
                }
                return price;
            }
        } catch (Exception e) {
            log.error("Failed to get index price from service for symbol={}", symbol, e);
        }
        return getIndexPriceFallback(symbol, new RuntimeException("Service unavailable"));
    }

    /**
     * 指数价格降级方法：从缓存获取
     */
    private long getIndexPriceFallback(String symbol, Throwable t) {
        log.warn("Using fallback for index price, symbol={}", symbol);
        if (redisTemplate != null) {
            Object cached = redisTemplate.opsForValue().get(CACHE_PREFIX_INDEX + symbol);
            if (cached != null) {
                log.info("Using cached index price for symbol={}", symbol);
                return (Long) cached;
            }
        }
        log.error("No cached index price available for symbol={}", symbol);
        throw new RuntimeException("Index price service unavailable and no cache");
    }

    /**
     * 获取标记价格（带缓存和降级）
     */
    @CircuitBreaker(name = "markPriceService", fallbackMethod = "getMarkPriceFallback")
    @Retry(name = "markPriceService")
    private long getMarkPrice(String symbol) {
        try {
            Result<MarkPriceDTO> result = markPriceClient.getMarkPrice(symbol);
            if (result != null && result.getData() != null) {
                long price = result.getData().getPrice();
                // 缓存到Redis，TTL=30秒
                if (redisTemplate != null) {
                    redisTemplate.opsForValue().set(CACHE_PREFIX_MARK + symbol, price, 30, TimeUnit.SECONDS);
                }
                return price;
            }
        } catch (Exception e) {
            log.error("Failed to get mark price from service for symbol={}", symbol, e);
        }
        return getMarkPriceFallback(symbol, new RuntimeException("Service unavailable"));
    }

    /**
     * 标记价格降级方法：从缓存获取
     */
    private long getMarkPriceFallback(String symbol, Throwable t) {
        log.warn("Using fallback for mark price, symbol={}", symbol);
        if (redisTemplate != null) {
            Object cached = redisTemplate.opsForValue().get(CACHE_PREFIX_MARK + symbol);
            if (cached != null) {
                log.info("Using cached mark price for symbol={}", symbol);
                return (Long) cached;
            }
        }
        log.error("No cached mark price available for symbol={}", symbol);
        throw new RuntimeException("Mark price service unavailable and no cache");
    }

    /**
     * 获取持仓统计
     */
    @CircuitBreaker(name = "positionService")
    @Retry(name = "positionService")
    private PositionStatsDTO getPositionStats(String symbol) {
        try {
            Result<PositionStatsDTO> result = positionClient.getPositionStats(symbol);
            if (result != null && result.getData() != null) {
                return result.getData();
            }
        } catch (Exception e) {
            log.error("Failed to get position stats for symbol={}", symbol, e);
        }
        // 返回空统计
        PositionStatsDTO stats = new PositionStatsDTO();
        stats.setSymbol(symbol);
        stats.setTotalLongQty(0L);
        stats.setTotalShortQty(0L);
        return stats;
    }

    /**
     * 获取所有持仓
     */
    @CircuitBreaker(name = "positionService")
    @Retry(name = "positionService")
    private List<PositionDTO> getAllPositions(String symbol) {
        try {
            Result<List<PositionDTO>> result = positionClient.getAllPositions(symbol);
            if (result != null && result.getData() != null) {
                return result.getData();
            }
        } catch (Exception e) {
            log.error("Failed to get all positions for symbol={}", symbol, e);
            return List.of();
        }
        return List.of();
    }

    /**
     * 创建Ledger分录
     */
    @Retry(name = "ledgerService")
    private void createLedgerEntry(Long userId, String symbol, Long fundingTime, String side, Long fundingFee, String marginMode) {
        try {
            FundingFeeLedgerRequest request = new FundingFeeLedgerRequest();
            request.setUserId(userId);
            request.setSymbol(symbol);
            request.setFundingTime(fundingTime);
            request.setSide(side);
            request.setFundingFee(fundingFee);
            request.setMarginMode(marginMode);
            request.setRemark("Funding fee settlement at " + fundingTime);

            Result<LedgerEntryDTO> result = ledgerClient.createFundingFeeLedger(request);
            if (result == null || result.getData() == null) {
                throw new RuntimeException("Ledger creation failed: empty result");
            }

            log.debug("Ledger entry created: userId={}, ledgerId={}", userId, result.getData().getLedgerId());

        } catch (Exception e) {
            log.error("Failed to create ledger entry for user={}, symbol={}", userId, symbol, e);
            throw new RuntimeException("Ledger service failed", e);
        }
    }

    /**
     * 发布资金费率计算完成事件
     */
    private void publishFundingRateCalcEvent(FundingRateHistory history) {
        FundingRateCalcEvent.FundingRateData data = new FundingRateCalcEvent.FundingRateData(
                history.getSymbol(),
                history.getFundingTime(),
                history.getFundingRate(),
                history.getMarkPrice(),
                history.getIndexPrice(),
                history.getPremiumIndex(),
                history.getTotalLongQty(),
                history.getTotalShortQty()
        );

        FundingRateCalcEvent event = new FundingRateCalcEvent();
        event.setEventTime(System.currentTimeMillis());
        event.setData(data);

        eventProducer.publishFundingRateCalcEvent(event);
    }

    /**
     * 发布资金费用结算完成事件
     */
    private void publishFundingSettlementEvent(String symbol, Long fundingTime, Integer totalUsers,
                                                 Long totalAmount, Integer longPayersCount,
                                                 Integer shortPayersCount, Long duration) {
        FundingSettlementEvent.SettlementData data = new FundingSettlementEvent.SettlementData(
                symbol,
                fundingTime,
                totalUsers,
                totalAmount,
                longPayersCount,
                shortPayersCount,
                duration
        );

        FundingSettlementEvent event = new FundingSettlementEvent();
        event.setEventTime(System.currentTimeMillis());
        event.setData(data);

        eventProducer.publishFundingSettlementEvent(event);
    }

    /**
     * 发布用户资金费用事件
     */
    private void publishUserFundingFeeEvent(UserFundingFee userFee, Long fundingRate) {
        UserFundingFeeEvent.UserFundingFeeData data = new UserFundingFeeEvent.UserFundingFeeData(
                userFee.getUserId(),
                userFee.getSymbol(),
                userFee.getFundingTime(),
                userFee.getSide(),
                userFee.getPositionQty(),
                fundingRate,
                userFee.getFundingFee(),
                userFee.getMarginMode()
        );

        UserFundingFeeEvent event = new UserFundingFeeEvent();
        event.setEventTime(System.currentTimeMillis());
        event.setData(data);

        eventProducer.publishUserFundingFeeEvent(event);
    }
}
