package com.exchange.tpsl.service;

import com.exchange.tpsl.dto.*;
import com.exchange.tpsl.entity.TpSlOrder;

import java.util.List;

/**
 * 止盈止损服务接口
 */
public interface TpSlService {

    /**
     * 创建TP/SL订单
     */
    Long createTpSlOrder(CreateTpSlRequest request);

    /**
     * 修改TP/SL订单
     */
    boolean modifyTpSlOrder(ModifyTpSlRequest request);

    /**
     * 撤销TP/SL订单
     */
    boolean cancelTpSlOrder(Long orderId);

    /**
     * 批量撤销TP/SL订单
     */
    int cancelBatch(Long userId, String symbol, Long positionId);

    /**
     * 根据持仓查询TP/SL订单
     */
    PositionTpSlVO getByPosition(Long positionId);

    /**
     * 查询用户的TP/SL订单列表
     */
    List<TpSlOrderVO> listOrders(Long userId, String symbol, String status);

    /**
     * 查询订单详情
     */
    TpSlOrderVO getOrderDetail(Long orderId);

    /**
     * 检查并触发TP/SL订单
     */
    void checkAndTrigger(String symbol, Long markPrice);

    /**
     * 持仓平仓后撤销关联TP/SL
     */
    void cancelByPositionClose(Long positionId);

    /**
     * 更新移动止损触发价
     */
    void updateTrailingStop(String symbol, Long markPrice);
}
