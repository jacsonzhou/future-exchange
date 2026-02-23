package com.exchange.tpsl.controller;

import com.exchange.common.core.R;
import com.exchange.tpsl.dto.*;
import com.exchange.tpsl.service.TpSlService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 止盈止损订单控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tp-sl")
public class TpSlController {

    @Autowired
    private TpSlService tpSlService;

    /**
     * 创建TP/SL订单
     */
    @PostMapping("/create")
    public R<Long> createTpSlOrder(@Validated @RequestBody CreateTpSlRequest request) {
        try {
            Long orderId = tpSlService.createTpSlOrder(request);
            return R.success(orderId);
        } catch (Exception e) {
            log.error("Failed to create TP/SL order", e);
            return R.error(e.getMessage());
        }
    }

    /**
     * 创建移动止损订单
     */
    @PostMapping("/create-trailing")
    public R<Long> createTrailingStop(@Validated @RequestBody CreateTpSlRequest request) {
        try {
            request.setType("TRAILING");
            Long orderId = tpSlService.createTpSlOrder(request);
            return R.success(orderId);
        } catch (Exception e) {
            log.error("Failed to create trailing stop order", e);
            return R.error(e.getMessage());
        }
    }

    /**
     * 修改TP/SL订单
     */
    @PostMapping("/modify")
    public R<Boolean> modifyTpSlOrder(@Validated @RequestBody ModifyTpSlRequest request) {
        try {
            boolean success = tpSlService.modifyTpSlOrder(request);
            return R.success(success);
        } catch (Exception e) {
            log.error("Failed to modify TP/SL order", e);
            return R.error(e.getMessage());
        }
    }

    /**
     * 撤销TP/SL订单
     */
    @PostMapping("/cancel")
    public R<Boolean> cancelTpSlOrder(@Validated @RequestBody CancelTpSlRequest request) {
        try {
            boolean success = tpSlService.cancelTpSlOrder(request.getTpSlOrderId());
            return R.success(success);
        } catch (Exception e) {
            log.error("Failed to cancel TP/SL order", e);
            return R.error(e.getMessage());
        }
    }

    /**
     * 批量撤销TP/SL订单
     */
    @PostMapping("/cancel-batch")
    public R<Integer> cancelBatch(@Validated @RequestBody BatchCancelRequest request) {
        try {
            int count = tpSlService.cancelBatch(
                request.getUserId(),
                request.getSymbol(),
                request.getPositionId()
            );
            return R.success(count);
        } catch (Exception e) {
            log.error("Failed to batch cancel TP/SL orders", e);
            return R.error(e.getMessage());
        }
    }

    /**
     * 查询用户的TP/SL订单列表
     */
    @GetMapping("/list")
    public R<List<TpSlOrderVO>> listOrders(
        @RequestParam Long userId,
        @RequestParam(required = false) String symbol,
        @RequestParam(required = false) String status
    ) {
        try {
            List<TpSlOrderVO> orders = tpSlService.listOrders(userId, symbol, status);
            return R.success(orders);
        } catch (Exception e) {
            log.error("Failed to list TP/SL orders", e);
            return R.error(e.getMessage());
        }
    }

    /**
     * 查询持仓关联的TP/SL订单
     */
    @GetMapping("/by-position")
    public R<PositionTpSlVO> getByPosition(@RequestParam Long positionId) {
        try {
            PositionTpSlVO vo = tpSlService.getByPosition(positionId);
            return R.success(vo);
        } catch (Exception e) {
            log.error("Failed to get TP/SL by position", e);
            return R.error(e.getMessage());
        }
    }

    /**
     * 查询订单详情
     */
    @GetMapping("/detail")
    public R<TpSlOrderVO> getOrderDetail(@RequestParam Long orderId) {
        try {
            TpSlOrderVO vo = tpSlService.getOrderDetail(orderId);
            return R.success(vo);
        } catch (Exception e) {
            log.error("Failed to get TP/SL order detail", e);
            return R.error(e.getMessage());
        }
    }
}
