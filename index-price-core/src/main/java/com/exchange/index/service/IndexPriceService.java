package com.exchange.index.service;

import com.exchange.index.dto.IndexPriceDTO;

import java.util.List;

/**
 * 指数价格服务接口
 */
public interface IndexPriceService {

    /**
     * 获取最新指数价格
     *
     * @param symbol 交易对
     * @return 指数价格DTO
     */
    IndexPriceDTO getLatestIndexPrice(String symbol);

    /**
     * 获取所有交易对的最新指数价格
     *
     * @return 指数价格列表
     */
    List<IndexPriceDTO> getAllLatestIndexPrices();

    /**
     * 计算并更新指数价格
     *
     * @param symbol 交易对
     */
    void calculateAndUpdateIndexPrice(String symbol);

    /**
     * 批量计算指数价格
     */
    void batchCalculateIndexPrices();
}
