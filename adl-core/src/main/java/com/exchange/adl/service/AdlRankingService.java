package com.exchange.adl.service;

import com.exchange.adl.entity.AdlRankingQueue;

import java.util.List;

/**
 * ADL排名服务接口
 */
public interface AdlRankingService {

    /**
     * 计算并更新ADL排名
     *
     * @param symbol 交易对
     * @param side 方向
     */
    void calculateAndUpdateRanking(String symbol, String side);

    /**
     * 批量计算ADL排名（所有交易对、所有方向）
     */
    void calculateAllRankings();

    /**
     * 获取ADL排名列表
     *
     * @param symbol 交易对
     * @param side 方向
     * @param limit 返回数量
     * @return 排名列表
     */
    List<AdlRankingQueue> getRankingList(String symbol, String side, int limit);

    /**
     * 获取用户ADL排名
     *
     * @param userId 用户ID
     * @param symbol 交易对
     * @return 排名信息
     */
    AdlRankingQueue getUserRanking(Long userId, String symbol);

    /**
     * 清除过期排名
     *
     * @param expireTime 过期时间（毫秒）
     */
    void clearExpiredRankings(long expireTime);
}
