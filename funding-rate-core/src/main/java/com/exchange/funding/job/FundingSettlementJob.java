package com.exchange.funding.job;

import com.exchange.funding.entity.FundingRateConfig;
import com.exchange.funding.lock.FundingSettlementLock;
import com.exchange.funding.mapper.FundingRateConfigMapper;
import com.exchange.funding.service.FundingRateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 资金费率结算定时任务
 *
 * 每8小时执行一次: 00:00, 08:00, 16:00 UTC
 */
@Slf4j
@Component
public class FundingSettlementJob {

    @Autowired
    private FundingRateService fundingRateService;

    @Autowired
    private FundingRateConfigMapper configMapper;

    @Autowired
    private FundingSettlementLock settlementLock;

    @Value("${funding-rate.settlement.enabled:true}")
    private Boolean settlementEnabled;

    @Value("${funding-rate.estimate.enabled:true}")
    private Boolean estimateEnabled;
    
    /**
     * 资金费率计算与结算
     *
     * 使用cron表达式: 每天 00:00, 08:00, 16:00 UTC 执行
     * 注意: 实际应根据服务器时区调整
     */
    @Scheduled(cron = "${funding-rate.settlement.cron:0 0 0,8,16 * * ?}")
    public void fundingSettlement() {
        if (!settlementEnabled) {
            log.debug("Funding settlement is disabled, skip");
            return;
        }

        log.info("Starting funding rate settlement job");
        long jobStartTime = System.currentTimeMillis();

        long fundingTime = System.currentTimeMillis();

        // 获取所有启用的资金费率配置
        List<FundingRateConfig> configs = configMapper.selectAllActive();
        log.info("Found {} active symbols for settlement", configs.size());

        int successCount = 0;
        int failureCount = 0;

        for (FundingRateConfig config : configs) {
            String symbol = config.getSymbol();

            // 使用分布式锁执行结算
            settlementLock.executeWithLock(symbol, fundingTime, () -> {
                try {
                    log.info("Processing funding settlement for symbol={}", symbol);

                    // 1. 计算资金费率
                    fundingRateService.calculateFundingRate(symbol, fundingTime);

                    // 2. 执行资金费用结算
                    fundingRateService.settleFundingFee(symbol, fundingTime);

                    log.info("Funding settlement completed for symbol={}", symbol);

                } catch (Exception e) {
                    log.error("Failed to process funding settlement for symbol={}", symbol, e);
                    // TODO: 添加到重试队列
                    throw new RuntimeException("Settlement failed for " + symbol, e);
                }
            });

            try {
                successCount++;
            } catch (Exception e) {
                failureCount++;
            }
        }

        long jobDuration = System.currentTimeMillis() - jobStartTime;
        log.info("Funding rate settlement job completed: total={}, success={}, failure={}, duration={}ms",
                configs.size(), successCount, failureCount, jobDuration);

        // TODO: 上报监控指标
    }
    
    /**
     * 预估费率计算（每5秒）
     * 更新预估资金费率缓存
     */
    @Scheduled(fixedRate = 5000)
    public void updateEstimatedRate() {
        if (!estimateEnabled) {
            return;
        }

        // 获取所有symbol并更新预估费率
        List<FundingRateConfig> configs = configMapper.selectAllActive();
        for (FundingRateConfig config : configs) {
            try {
                String symbol = config.getSymbol();

                // 计算预估费率
                Long estimatedRate = fundingRateService.getEstimatedFundingRate(symbol);
                Long nextFundingTime = fundingRateService.getNextFundingTime(symbol);

                // 更新预估费率到数据库
                fundingRateService.updateEstimatedRate(symbol, estimatedRate, nextFundingTime);

                log.debug("Updated estimated rate for symbol={}: rate={}, nextTime={}",
                        symbol, estimatedRate, nextFundingTime);

            } catch (Exception e) {
                log.error("Failed to update estimated rate for symbol={}", config.getSymbol(), e);
            }
        }
    }
}
