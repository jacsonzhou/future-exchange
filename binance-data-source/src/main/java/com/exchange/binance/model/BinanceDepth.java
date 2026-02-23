package com.exchange.binance.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 币安深度数据模型
 * 
 * 对应币安WebSocket depthUpdate事件
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BinanceDepth {

    /**
     * 交易对，如 BTCUSDT
     */
    private String symbol;

    /**
     * 事件时间（毫秒）
     */
    private long eventTime;

    /**
     * 本次更新的第一个序号
     */
    private long firstUpdateId;

    /**
     * 本次更新的最后一个序号
     */
    private long lastUpdateId;

    /**
     * 买盘深度 [[price, qty], ...]
     * price和qty都是8位精度的long值
     */
    @Builder.Default
    private List<long[]> bids = new ArrayList<>();

    /**
     * 卖盘深度 [[price, qty], ...]
     * price和qty都是8位精度的long值
     */
    @Builder.Default
    private List<long[]> asks = new ArrayList<>();

    /**
     * 是否为快照（首次全量数据）
     */
    @Builder.Default
    private boolean snapshot = false;

    /**
     * 获取买盘最优价格（最高买价）
     * 
     * @return [price, qty]，如果没有买盘返回 [0, 0]
     */
    public long[] getBestBid() {
        if (bids == null || bids.isEmpty()) {
            return new long[]{0, 0};
        }
        return bids.get(0);
    }

    /**
     * 获取卖盘最优价格（最低卖价）
     * 
     * @return [price, qty]，如果没有卖盘返回 [0, 0]
     */
    public long[] getBestAsk() {
        if (asks == null || asks.isEmpty()) {
            return new long[]{0, 0};
        }
        return asks.get(0);
    }

    /**
     * 获取买卖价差
     * 
     * @return 价差（ask - bid），如果没有数据返回0
     */
    public long getSpread() {
        long[] bestBid = getBestBid();
        long[] bestAsk = getBestAsk();
        
        if (bestBid[0] == 0 || bestAsk[0] == 0) {
            return 0;
        }
        
        return bestAsk[0] - bestBid[0];
    }

    /**
     * 获取买盘总量
     */
    public long getTotalBidQty() {
        if (bids == null) {
            return 0;
        }
        return bids.stream().mapToLong(b -> b[1]).sum();
    }

    /**
     * 获取卖盘总量
     */
    public long getTotalAskQty() {
        if (asks == null) {
            return 0;
        }
        return asks.stream().mapToLong(a -> a[1]).sum();
    }

    /**
     * 检查数据是否有效
     */
    public boolean isValid() {
        return symbol != null && !symbol.isEmpty() && 
               lastUpdateId > 0 &&
               (bids != null || asks != null);
    }

    @Override
    public String toString() {
        return String.format("BinanceDepth[symbol=%s, updateId=%d, bids=%d, asks=%d]",
                symbol, lastUpdateId, 
                bids != null ? bids.size() : 0,
                asks != null ? asks.size() : 0);
    }
}
