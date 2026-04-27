package com.exchange.market.service;

import com.exchange.market.model.Kline;
import com.exchange.market.repository.KlineRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * K 线服务
 * 
 * 提供 K 线数据存取服务
 */
@Slf4j
@Service
public class KlineService {

    @Autowired
    private KlineRepository klineRepository;

    /**
     * 保存 K 线数据
     */
    public void saveKline(Kline kline) {
        klineRepository.save(kline);
    }

    /**
     * 批量保存 K 线数据
     */
    public void saveKlines(List<Kline> klines) {
        klineRepository.saveBatch(klines);
    }

    /**
     * 保存实时 K 线
     */
    public void saveRealtimeKline(Kline kline) {
        klineRepository.saveRealtime(kline);
    }

    /**
     * 获取 K 线数据
     * 
     * @param symbol    交易对
     * @param interval  周期
     * @param startTime 开始时间（毫秒）
     * @param endTime   结束时间（毫秒）
     * @param limit     限制条数
     */
    public List<Kline> getKlines(String symbol, String interval, 
                                  Long startTime, Long endTime, Integer limit) {
        if (klineRepository.supports1mAggregation(interval)) {
            // 回补高并发写入期间，聚合查询可能偶发超时，增加轻量重试避免退化到缓存少量数据。
            RuntimeException lastError = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    List<Kline> aggregated = klineRepository.queryAggregatedFrom1m(symbol, interval, startTime, endTime, limit);
                    if (!aggregated.isEmpty()) {
                        return aggregated;
                    }
                    break;
                } catch (RuntimeException e) {
                    lastError = e;
                    if (attempt >= 3) {
                        break;
                    }
                    try {
                        Thread.sleep(100L * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (lastError != null) {
                log.warn("[KlineService] Aggregation failed after retries, fallback to raw interval query: {} {}", symbol, interval, lastError);
            } else {
                log.debug("[KlineService] Aggregated result empty, fallback to raw interval query: {} {}", symbol, interval);
            }
        }
        return klineRepository.query(symbol, interval, startTime, endTime, limit);
    }

    /**
     * 获取最近 N 条 K 线
     */
    public List<Kline> getRecentKlines(String symbol, String interval, int limit) {
        return getKlines(symbol, interval, null, null, limit);
    }

    /**
     * 获取最新 K 线
     */
    public Kline getLatestKline(String symbol, String interval) {
        return klineRepository.getLatest(symbol, interval);
    }

    /**
     * 查询历史表最新开盘时间。
     */
    public Long getLatestOpenTime(String symbol, String interval) {
        return klineRepository.getLatestOpenTime(symbol, interval);
    }

    /**
     * 查询历史表最早开盘时间。
     */
    public Long getEarliestOpenTime(String symbol, String interval) {
        return klineRepository.getEarliestOpenTime(symbol, interval);
    }

    /**
     * 清空所有K线历史与实时数据。
     */
    public void truncateAllKlines() {
        klineRepository.truncateAllKlines();
    }

    /**
     * 删除指定symbol+interval的时间区间K线。
     */
    public void deleteKlines(String symbol, String interval, Long startTime, Long endTime) {
        klineRepository.deleteRange(symbol, interval, startTime, endTime);
    }

    /**
     * 查询时间段内已有open_time（升序去重）。
     */
    public List<Long> listOpenTimes(String symbol, String interval, long startInclusive, long endInclusive, int limit) {
        return klineRepository.listOpenTimes(symbol, interval, startInclusive, endInclusive, limit);
    }

    /**
     * 删除cutoff之前K线。
     */
    public void deleteKlinesBefore(long cutoffOpenTimeMs) {
        klineRepository.deleteBefore(cutoffOpenTimeMs);
    }

    /**
     * 关闭 K 线（将实时表数据写入历史表）
     */
    public void closeKline(Kline kline) {
        // 幂等保存到历史表（避免回补/实时边界重复插入）
        klineRepository.saveIfAbsent(kline);
        log.info("[KlineService] Closed kline: {} {} @ {}", 
            kline.getSymbol(), kline.getInterval(), kline.getOpenTime());
    }
}
