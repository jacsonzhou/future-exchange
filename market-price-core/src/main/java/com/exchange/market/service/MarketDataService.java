package com.exchange.market.service;

import com.exchange.market.entity.Kline;
import com.exchange.market.entity.Ticker24h;

import java.util.List;

/**
 * 行情数据服务接口
 */
public interface MarketDataService {

    /**
     * 获取K线数据
     *
     * @param symbol   交易对
     * @param interval 周期
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param limit     限制条数
     * @return K线列表
     */
    List<Kline> getKlines(String symbol, String interval, Long startTime, Long endTime, Integer limit);

    /**
     * 获取最新K线
     */
    Kline getLatestKline(String symbol, String interval);

    /**
     * 获取24小时统计
     */
    Ticker24h getTicker24h(String symbol);

    /**
     * 获取所有交易对的24小时统计
     */
    List<Ticker24h> getAllTickers24h();

    /**
     * 处理成交事件，更新K线和统计
     */
    void onTradeEvent(String symbol, Long price, Long quantity, Long tradeTime, boolean isBuyerMaker);
}
