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
        return klineRepository.query(symbol, interval, startTime, endTime, limit);
    }

    /**
     * 获取最近 N 条 K 线
     */
    public List<Kline> getRecentKlines(String symbol, String interval, int limit) {
        return klineRepository.getRecent(symbol, interval, limit);
    }

    /**
     * 获取最新 K 线
     */
    public Kline getLatestKline(String symbol, String interval) {
        return klineRepository.getLatest(symbol, interval);
    }

    /**
     * 关闭 K 线（将实时表数据写入历史表）
     */
    public void closeKline(Kline kline) {
        // 保存到历史表
        saveKline(kline);
        log.info("[KlineService] Closed kline: {} {} @ {}", 
            kline.getSymbol(), kline.getInterval(), kline.getOpenTime());
    }
}
