package com.exchange.markprice.service;

import com.exchange.markprice.dto.MarkPriceDTO;

import java.util.List;

/**
 * 标记价格服务接口
 */
public interface MarkPriceService {

    /**
     * 获取最新标记价格
     *
     * @param symbol 交易对
     * @return 标记价格DTO
     */
    MarkPriceDTO getLatestMarkPrice(String symbol);

    /**
     * 获取所有交易对的最新标记价格
     *
     * @return 标记价格列表
     */
    List<MarkPriceDTO> getAllLatestMarkPrices();

    /**
     * 计算并更新标记价格
     *
     * @param symbol 交易对
     */
    void calculateAndUpdateMarkPrice(String symbol);

    /**
     * 批量计算标记价格
     */
    void batchCalculateMarkPrices();

    /**
     * 处理指数价格更新
     *
     * @param symbol 交易对
     * @param indexPrice 指数价格
     */
    void onIndexPriceUpdate(String symbol, Long indexPrice);
}
