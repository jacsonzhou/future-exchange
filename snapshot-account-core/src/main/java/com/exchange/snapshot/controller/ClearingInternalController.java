package com.exchange.snapshot.controller;

import com.exchange.common.core.Result;
import com.exchange.snapshot.dto.AdlClearingApplyResult;
import com.exchange.snapshot.dto.AdlClearingRequest;
import com.exchange.snapshot.service.AccountSnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * ADL内部记账接口（供 adl-core 调用）。
 */
@Slf4j
@RestController
@RequestMapping("/internal/clearing")
public class ClearingInternalController {

    @Autowired
    private AccountSnapshotService accountSnapshotService;

    @PostMapping("/adl")
    public Result<Map<String, Object>> applyAdl(@RequestBody AdlClearingRequest request) {
        try {
            AdlClearingApplyResult result = accountSnapshotService.applyAdlClearing(request);
            Map<String, Object> data = new HashMap<>();
            data.put("bizSeq", result.getBizSeq());
            data.put("ledgerIds", result.getLedgerIds());
            data.put("idempotent", result.isIdempotent());
            return Result.success(data);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("[ClearingInternalController] Apply ADL clearing failed", e);
            return Result.error("apply adl clearing failed: " + e.getMessage());
        }
    }

    @GetMapping("/check")
    public Result<Map<String, Object>> check(@RequestParam("bizSeq") String bizSeq) {
        Map<String, Object> data = new HashMap<>();
        data.put("exists", accountSnapshotService.hasProcessedAdlClearing(bizSeq));
        return Result.success(data);
    }
}
