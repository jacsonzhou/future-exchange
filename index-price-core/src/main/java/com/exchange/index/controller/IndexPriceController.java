package com.exchange.index.controller;

import com.exchange.common.core.Result;
import com.exchange.index.dto.IndexPriceDTO;
import com.exchange.index.service.IndexPriceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 指数价格控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/index-price")
public class IndexPriceController {

    @Autowired
    private IndexPriceService indexPriceService;

    /**
     * 获取最新指数价格
     */
    @GetMapping("/latest")
    public Result<IndexPriceDTO> getLatestIndexPrice(@RequestParam("symbol") String symbol) {
        IndexPriceDTO dto = indexPriceService.getLatestIndexPrice(symbol);
        if (dto == null) {
            return Result.error("Index price not found for symbol: " + symbol);
        }
        return Result.success(dto);
    }

    /**
     * 获取所有交易对的最新指数价格
     */
    @GetMapping("/all")
    public Result<List<IndexPriceDTO>> getAllLatestIndexPrices() {
        List<IndexPriceDTO> prices = indexPriceService.getAllLatestIndexPrices();
        return Result.success(prices);
    }

    /**
     * 手动触发指数价格计算
     */
    @PostMapping("/calculate")
    public Result<Void> calculateIndexPrice(@RequestParam("symbol") String symbol) {
        indexPriceService.calculateAndUpdateIndexPrice(symbol);
        return Result.success();
    }
}
