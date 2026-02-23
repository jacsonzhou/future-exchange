package com.exchange.markprice.controller;

import com.exchange.common.core.Result;
import com.exchange.markprice.dto.MarkPriceDTO;
import com.exchange.markprice.service.MarkPriceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 标记价格控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/mark-price")
public class MarkPriceController {

    @Autowired
    private MarkPriceService markPriceService;

    /**
     * 获取最新标记价格
     */
    @GetMapping("/latest")
    public Result<MarkPriceDTO> getLatestMarkPrice(@RequestParam("symbol") String symbol) {
        MarkPriceDTO dto = markPriceService.getLatestMarkPrice(symbol);
        if (dto == null) {
            return Result.error("Mark price not found for symbol: " + symbol);
        }
        return Result.success(dto);
    }

    /**
     * 获取所有交易对的最新标记价格
     */
    @GetMapping("/all")
    public Result<List<MarkPriceDTO>> getAllLatestMarkPrices() {
        List<MarkPriceDTO> prices = markPriceService.getAllLatestMarkPrices();
        return Result.success(prices);
    }

    /**
     * 手动触发标记价格计算
     */
    @PostMapping("/calculate")
    public Result<Void> calculateMarkPrice(@RequestParam("symbol") String symbol) {
        markPriceService.calculateAndUpdateMarkPrice(symbol);
        return Result.success();
    }
}
