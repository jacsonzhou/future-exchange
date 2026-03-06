package com.exchange.user.client;

import com.exchange.user.controller.ProfileCenterController;
import com.exchange.user.controller.TradingViewController;
import com.exchange.user.dto.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "position-snapshot-core")
public interface PositionSnapshotClient {

    @GetMapping("/internal/position/{userId}/all")
    List<ProfileCenterController.PositionSnapshotDTO> getAllPositions(@PathVariable("userId") Long userId);

    @GetMapping("/api/v1/position/list")
    Result<List<TradingViewController.PositionResponse>> getPositions(
            @RequestParam("accountId") Long accountId,
            @RequestParam("symbol") String symbol
    );
}
