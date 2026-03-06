package com.exchange.user.client;

import com.exchange.user.controller.TradingViewController;
import com.exchange.user.dto.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "market-price-core")
public interface MarketPriceClient {

    @GetMapping("/api/v1/market/depth")
    Result<TradingViewController.OrderBookResponse> getOrderBook(
            @RequestParam("symbol") String symbol,
            @RequestParam("limit") Integer limit
    );

    @GetMapping("/api/v1/market/trades")
    Result<List<TradingViewController.RecentTradeResponse>> getRecentTrades(
            @RequestParam("symbol") String symbol,
            @RequestParam("limit") Integer limit
    );

    @GetMapping("/api/v1/market/klines")
    Result<List<TradingViewController.KlineResponse>> getKlines(
            @RequestParam("symbol") String symbol,
            @RequestParam("interval") String interval,
            @RequestParam("limit") Integer limit
    );

    @GetMapping("/api/v1/market/ticker/24h")
    Result<TradingViewController.Ticker24hResponse> get24hTicker(@RequestParam("symbol") String symbol);
}
