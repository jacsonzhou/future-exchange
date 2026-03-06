package com.exchange.user.client;

import com.exchange.user.controller.ProfileCenterController;
import com.exchange.user.controller.TradingViewController;
import com.exchange.user.dto.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "snapshot-account-core")
public interface SnapshotAccountClient {

    @GetMapping("/internal/snapshot/account/{userId}")
    ProfileCenterController.SnapshotAccountDTO getAccountSnapshot(@PathVariable("userId") Long userId);

    @GetMapping("/api/v1/account/balance")
    Result<TradingViewController.AccountBalanceResponse> getBalance(@RequestParam("accountId") Long accountId);
}
