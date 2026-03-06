package com.exchange.user.controller;

import com.exchange.user.client.MarketPriceClient;
import com.exchange.user.client.OmsOrderClient;
import com.exchange.user.client.PositionSnapshotClient;
import com.exchange.user.client.SnapshotAccountClient;
import com.exchange.user.dto.*;
import com.exchange.user.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 交易界面数据控制器
 * 
 * 聚合用户进入交易界面所需的所有数据：
 * 1. 用户信息 + 账户信息
 * 2. 资金余额
 * 3. 当前持仓
 * 4. 当前订单
 * 5. 成交历史
 * 
 * 这些数据来自不同的服务，在此聚合返回
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/trading")
@RequiredArgsConstructor
public class TradingViewController {

    private final JwtUtil jwtUtil;
    private final SnapshotAccountClient snapshotAccountClient;
    private final PositionSnapshotClient positionSnapshotClient;
    private final OmsOrderClient omsOrderClient;
    private final MarketPriceClient marketPriceClient;

    /**
     * 获取交易界面完整数据
     * 
     * 用户进入交易界面时调用，一次性返回所有必要数据
     * 
     * @param symbol 交易对，如 BTCUSDT
     * @param token JWT Token
     * @return 交易界面完整数据
     */
    @GetMapping("/dashboard")
    public Result<TradingDashboardResponse> getDashboard(
            @RequestParam String symbol,
            @RequestHeader("Authorization") String token) {
        
        Long userId = extractUserId(token);
        Long accountId = extractAccountId(token);
        
        log.info("[TradingViewController] Get dashboard for userId: {}, accountId: {}, symbol: {}", 
                userId, accountId, symbol);

        TradingDashboardResponse dashboard = new TradingDashboardResponse();
        
        // 1. 获取资金余额
        try {
            Result<AccountBalanceResponse> balanceResult = snapshotAccountClient.getBalance(accountId);
            if (balanceResult.getCode() == 200) {
                dashboard.setBalance(balanceResult.getData());
            }
        } catch (Exception e) {
            log.error("[TradingViewController] Failed to get balance", e);
        }

        // 2. 获取当前持仓
        try {
            Result<List<PositionResponse>> positionResult = positionSnapshotClient.getPositions(accountId, symbol);
            if (positionResult.getCode() == 200) {
                dashboard.setPositions(positionResult.getData());
            }
        } catch (Exception e) {
            log.error("[TradingViewController] Failed to get positions", e);
        }

        // 3. 获取当前订单
        try {
            Result<List<OrderResponse>> orderResult = omsOrderClient.getOpenOrders(accountId, symbol);
            if (orderResult.getCode() == 200) {
                dashboard.setOpenOrders(orderResult.getData());
            }
        } catch (Exception e) {
            log.error("[TradingViewController] Failed to get open orders", e);
        }

        // 4. 获取最近成交
        try {
            Result<List<TradeResponse>> tradeResult = omsOrderClient.getRecentTrades(accountId, symbol, 20);
            if (tradeResult.getCode() == 200) {
                dashboard.setRecentTrades(tradeResult.getData());
            }
        } catch (Exception e) {
            log.error("[TradingViewController] Failed to get recent trades", e);
        }

        // 5. 获取市场行情数据（无需认证）
        try {
            Result<OrderBookResponse> orderBookResult = marketPriceClient.getOrderBook(symbol, 20);
            if (orderBookResult.getCode() == 200) {
                dashboard.setOrderBook(orderBookResult.getData());
            }
        } catch (Exception e) {
            log.error("[TradingViewController] Failed to get order book", e);
        }

        try {
            Result<List<RecentTradeResponse>> recentTradesResult = marketPriceClient.getRecentTrades(symbol, 50);
            if (recentTradesResult.getCode() == 200) {
                dashboard.setMarketTrades(recentTradesResult.getData());
            }
        } catch (Exception e) {
            log.error("[TradingViewController] Failed to get market trades", e);
        }

        try {
            Result<List<KlineResponse>> klineResult = marketPriceClient.getKlines(symbol, "1m", 500);
            if (klineResult.getCode() == 200) {
                dashboard.setKlines(klineResult.getData());
            }
        } catch (Exception e) {
            log.error("[TradingViewController] Failed to get klines", e);
        }

        // 6. 获取24小时统计
        try {
            Result<Ticker24hResponse> tickerResult = marketPriceClient.get24hTicker(symbol);
            if (tickerResult.getCode() == 200) {
                dashboard.setTicker24h(tickerResult.getData());
            }
        } catch (Exception e) {
            log.error("[TradingViewController] Failed to get 24h ticker", e);
        }

        return Result.success(dashboard);
    }

    /**
     * 快速查询资金余额
     */
    @GetMapping("/balance")
    public Result<AccountBalanceResponse> getBalance(@RequestHeader("Authorization") String token) {
        Long accountId = extractAccountId(token);
        return snapshotAccountClient.getBalance(accountId);
    }

    /**
     * 快速查询持仓
     */
    @GetMapping("/positions")
    public Result<List<PositionResponse>> getPositions(
            @RequestParam String symbol,
            @RequestHeader("Authorization") String token) {
        Long accountId = extractAccountId(token);
        return positionSnapshotClient.getPositions(accountId, symbol);
    }

    /**
     * 快速查询当前订单
     */
    @GetMapping("/orders/open")
    public Result<List<OrderResponse>> getOpenOrders(
            @RequestParam String symbol,
            @RequestHeader("Authorization") String token) {
        Long accountId = extractAccountId(token);
        return omsOrderClient.getOpenOrders(accountId, symbol);
    }

    // ==================== Helper Methods ====================

    private Long extractUserId(String token) {
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        return jwtUtil.getUserIdFromToken(token);
    }

    private Long extractAccountId(String token) {
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        return jwtUtil.getAccountIdFromToken(token);
    }

    // ==================== DTO Classes ====================

    /**
     * 交易界面完整数据响应
     */
    @lombok.Data
    public static class TradingDashboardResponse {
        // 用户资金
        private AccountBalanceResponse balance;
        
        // 当前持仓
        private List<PositionResponse> positions;
        
        // 当前订单
        private List<OrderResponse> openOrders;
        
        // 最近成交
        private List<TradeResponse> recentTrades;
        
        // 市场数据
        private OrderBookResponse orderBook;           // 盘口深度
        private List<RecentTradeResponse> marketTrades; // 市场成交
        private List<KlineResponse> klines;            // K线数据
        private Ticker24hResponse ticker24h;           // 24小时统计
    }

    @lombok.Data
    public static class AccountBalanceResponse {
        private Long accountId;
        private List<AssetBalance> balances;
    }

    @lombok.Data
    public static class AssetBalance {
        private String asset;           // USDT, BTC, etc.
        private BigDecimal total;       // 总余额
        private BigDecimal available;   // 可用余额
        private BigDecimal frozen;      // 冻结余额
    }

    @lombok.Data
    public static class PositionResponse {
        private String symbol;
        private String side;            // LONG / SHORT
        private BigDecimal size;        // 仓位大小
        private BigDecimal entryPrice;  // 开仓均价
        private BigDecimal markPrice;   // 标记价格
        private BigDecimal liquidationPrice; // 强平价格
        private BigDecimal unrealizedPnl;    // 未实现盈亏
        private BigDecimal margin;      // 占用保证金
        private BigDecimal leverage;    // 杠杆倍数
    }

    @lombok.Data
    public static class OrderResponse {
        private String orderId;
        private String symbol;
        private String side;            // BUY / SELL
        private String type;            // LIMIT / MARKET
        private BigDecimal price;
        private BigDecimal quantity;
        private BigDecimal filledQuantity;
        private String status;          // NEW / PARTIALLY_FILLED / FILLED / CANCELED
        private Long createTime;
    }

    @lombok.Data
    public static class TradeResponse {
        private String tradeId;
        private String orderId;
        private String symbol;
        private String side;
        private BigDecimal price;
        private BigDecimal quantity;
        private BigDecimal fee;
        private Long time;
    }

    @lombok.Data
    public static class OrderBookResponse {
        private String symbol;
        private Long lastUpdateId;
        private List<OrderBookEntry> bids; // 买单 [[price, qty], ...]
        private List<OrderBookEntry> asks; // 卖单 [[price, qty], ...]
    }

    @lombok.Data
    public static class OrderBookEntry {
        private BigDecimal price;
        private BigDecimal quantity;
    }

    @lombok.Data
    public static class RecentTradeResponse {
        private String tradeId;
        private BigDecimal price;
        private BigDecimal quantity;
        private String side;        // BUY / SELL (taker side)
        private Long time;
    }

    @lombok.Data
    public static class KlineResponse {
        private Long openTime;
        private BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private BigDecimal volume;
        private Long closeTime;
    }

    @lombok.Data
    public static class Ticker24hResponse {
        private String symbol;
        private BigDecimal priceChange;
        private BigDecimal priceChangePercent;
        private BigDecimal weightedAvgPrice;
        private BigDecimal lastPrice;
        private BigDecimal highPrice;
        private BigDecimal lowPrice;
        private BigDecimal volume;
        private Long openTime;
        private Long closeTime;
        private BigDecimal openPrice;
    }
}
