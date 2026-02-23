package com.exchange.market.controller;

import com.exchange.common.core.ApiResponse;
import com.exchange.market.engine.OrderBook;
import com.exchange.market.entity.Kline;
import com.exchange.market.entity.Ticker24h;
import com.exchange.market.service.MarketDataService;
import com.exchange.market.service.impl.MarketDataServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 行情数据REST API控制器
 * 
 * 对标Binance API:
 * - GET /api/v1/depth
 * - GET /api/v1/trades
 * - GET /api/v1/klines
 * - GET /api/v1/ticker/24hr
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MarketDataController {

    private final MarketDataService marketDataService;
    private final MarketDataServiceImpl marketDataServiceImpl;

    /**
     * 获取深度
     * 
     * @param symbol 交易对 (e.g. BTCUSDT)
     * @param limit 深度档位数 (5, 10, 20, 50, 100, 500, 1000)
     */
    @GetMapping("/depth")
    public ApiResponse<DepthResponse> getDepth(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "100") int limit) {
        
        try {
            OrderBook.DepthSnapshot snapshot = marketDataServiceImpl.getDepthSnapshot(symbol, limit);
            
            if (snapshot == null) {
                return ApiResponse.error(404, "Symbol not found: " + symbol);
            }
            
            DepthResponse response = new DepthResponse();
            response.setLastUpdateId(snapshot.getLastUpdateId());
            response.setMessageOutputTime(snapshot.getMessageOutputTime());
            response.setSymbol(snapshot.getSymbol());
            response.setBids(convertToStringArray(snapshot.getBids()));
            response.setAsks(convertToStringArray(snapshot.getAsks()));
            
            return ApiResponse.success(response);
            
        } catch (Exception e) {
            log.error("[API] Failed to get depth for {}: {}", symbol, e.getMessage());
            return ApiResponse.error(500, "Internal error: " + e.getMessage());
        }
    }

    /**
     * 获取K线数据
     * 
     * @param symbol 交易对
     * @param interval 周期 (1s, 1m, 5m, 15m, 30m, 1h, 2h, 4h, 6h, 8h, 12h, 1d, 3d, 1w, 1M)
     * @param startTime 开始时间戳 (可选)
     * @param endTime 结束时间戳 (可选)
     * @param limit 返回条数 (默认500, 最大1000)
     */
    @GetMapping("/klines")
    public ApiResponse<List<Object[]>> getKlines(
            @RequestParam String symbol,
            @RequestParam String interval,
            @RequestParam(required = false) Long startTime,
            @RequestParam(required = false) Long endTime,
            @RequestParam(required = false, defaultValue = "500") Integer limit) {
        
        try {
            List<Kline> klines = marketDataService.getKlines(symbol, interval, startTime, endTime, limit);
            
            // 转换为数组格式（与Binance兼容）
            List<Object[]> result = klines.stream()
                    .map(this::convertKlineToArray)
                    .toList();
            
            return ApiResponse.success(result);
            
        } catch (Exception e) {
            log.error("[API] Failed to get klines for {} {}: {}", symbol, interval, e.getMessage());
            return ApiResponse.error(500, "Internal error: " + e.getMessage());
        }
    }

    /**
     * 获取24小时统计
     * 
     * @param symbol 交易对 (可选，不提供则返回所有)
     */
    @GetMapping("/ticker/24hr")
    public ApiResponse<?> getTicker24h(@RequestParam(required = false) String symbol) {
        try {
            if (symbol != null) {
                Ticker24h ticker = marketDataService.getTicker24h(symbol);
                if (ticker == null) {
                    return ApiResponse.error(404, "Symbol not found: " + symbol);
                }
                return ApiResponse.success(ticker);
            } else {
                List<Ticker24h> tickers = marketDataService.getAllTickers24h();
                return ApiResponse.success(tickers);
            }
        } catch (Exception e) {
            log.error("[API] Failed to get ticker: {}", e.getMessage());
            return ApiResponse.error(500, "Internal error: " + e.getMessage());
        }
    }

    /**
     * 获取最优盘口（BBO）
     */
    @GetMapping("/bookTicker")
    public ApiResponse<BookTickerResponse> getBookTicker(@RequestParam String symbol) {
        try {
            long[] bbo = marketDataServiceImpl.getBBO(symbol);
            
            BookTickerResponse response = new BookTickerResponse();
            response.setSymbol(symbol);
            response.setBidPrice(String.valueOf(bbo[0]));
            response.setBidQty(String.valueOf(bbo[1]));
            response.setAskPrice(String.valueOf(bbo[2]));
            response.setAskQty(String.valueOf(bbo[3]));
            
            return ApiResponse.success(response);
            
        } catch (Exception e) {
            log.error("[API] Failed to get book ticker for {}: {}", symbol, e.getMessage());
            return ApiResponse.error(500, "Internal error: " + e.getMessage());
        }
    }

    /**
     * 获取服务健康状态
     */
    @GetMapping("/health")
    public ApiResponse<String> health() {
        return ApiResponse.success("OK");
    }

    // ========== 辅助方法 ==========

    private String[][] convertToStringArray(List<long[]> list) {
        if (list == null) return new String[0][];
        
        String[][] result = new String[list.size()][2];
        for (int i = 0; i < list.size(); i++) {
            long[] item = list.get(i);
            result[i][0] = String.valueOf(item[0]);
            result[i][1] = String.valueOf(item[1]);
        }
        return result;
    }

    private Object[] convertKlineToArray(Kline k) {
        return new Object[] {
            k.getOpenTime(),           // 开盘时间
            k.getOpenPrice(),          // 开盘价
            k.getHighPrice(),          // 最高价
            k.getLowPrice(),           // 最低价
            k.getClosePrice(),         // 收盘价
            k.getVolume(),             // 成交量
            k.getCloseTime(),          // 收盘时间
            k.getQuoteVolume(),        // 成交额
            k.getTradeCount(),         // 成交笔数
            k.getTakerBuyVolume(),     // 主动买入成交量
            k.getTakerBuyQuoteVolume() // 主动买入成交额
        };
    }

    // ========== DTO 类 ==========

    @lombok.Data
    public static class DepthResponse {
        private String symbol;
        private long lastUpdateId;
        private long messageOutputTime;
        private String[][] bids;
        private String[][] asks;
    }

    @lombok.Data
    public static class BookTickerResponse {
        private String symbol;
        private String bidPrice;
        private String bidQty;
        private String askPrice;
        private String askQty;
    }
}
