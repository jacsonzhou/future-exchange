package com.exchange.user.client;

import com.exchange.user.controller.ProfileCenterController;
import com.exchange.user.controller.TradingViewController;
import com.exchange.user.dto.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "oms-core", contextId = "userProfileOmsOrderClient")
public interface OmsOrderClient {

    @GetMapping("/api/v1/oms/order/list")
    ProfileCenterController.OmsOrderListResponse queryOrderList(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "offset", required = false, defaultValue = "0") Integer offset,
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit
    );

    @GetMapping("/api/v1/order/open")
    Result<List<TradingViewController.OrderResponse>> getOpenOrders(
            @RequestParam("accountId") Long accountId,
            @RequestParam("symbol") String symbol
    );

    @GetMapping("/api/v1/order/trades/recent")
    Result<List<TradingViewController.TradeResponse>> getRecentTrades(
            @RequestParam("accountId") Long accountId,
            @RequestParam("symbol") String symbol,
            @RequestParam("limit") Integer limit
    );
}
