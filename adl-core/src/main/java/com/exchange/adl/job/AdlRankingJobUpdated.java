package com.exchange.adl.job;

import com.exchange.adl.service.AdlRankingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ADL排名定时更新任务（集成版）
 *
 * 职责：
 * 1. 定时从Position Service获取持仓数据
 * 2. 计算ADL得分并排序
 * 3. 更新到adl_ranking_queue表
 * 4. 清理过期排名
 */
@Slf4j
@Component
public class AdlRankingJobUpdated {

    @Autowired
    private AdlRankingService adlRankingService;

    /**
     * 每5秒更新ADL排名队列
     */
    @Scheduled(fixedRate = 5000)
    public void updateAdlRanking() {
        try {
            log.debug("Starting ADL ranking update job...");

            // 计算所有交易对和方向的ADL排名
            adlRankingService.calculateAllRankings();

            log.debug("ADL ranking update job completed");

        } catch (Exception e) {
            log.error("Failed to update ADL ranking", e);
        }
    }

    /**
     * 每小时清理过期排名（超过1小时未更新的）
     */
    @Scheduled(fixedRate = 3600000) // 1小时
    public void clearExpiredRankings() {
        try {
            log.info("Starting to clear expired ADL rankings...");

            long expireTime = System.currentTimeMillis() - 3600000; // 1小时前
            adlRankingService.clearExpiredRankings(expireTime);

            log.info("Expired ADL rankings cleared");

        } catch (Exception e) {
            log.error("Failed to clear expired rankings", e);
        }
    }
}
