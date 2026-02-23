package com.exchange.tpsl.service.impl;

import com.exchange.tpsl.client.OmsClient;
import com.exchange.tpsl.dto.*;
import com.exchange.tpsl.entity.TpSlExecLog;
import com.exchange.tpsl.entity.TpSlOrder;
import com.exchange.tpsl.exception.TpSlException;
import com.exchange.tpsl.mapper.TpSlExecLogMapper;
import com.exchange.tpsl.mapper.TpSlOrderMapper;
import com.exchange.tpsl.publisher.TpSlEventPublisher;
import com.exchange.tpsl.service.RiskCheckService;
import com.exchange.tpsl.service.TpSlService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 止盈止损服务完整实现
 */
@Slf4j
@Service
public class TpSlServiceImpl implements TpSlService {

    @Autowired
    private TpSlOrderMapper tpSlOrderMapper;

    @Autowired
    private TpSlExecLogMapper execLogMapper;

    @Autowired
    private RiskCheckService riskCheckService;

    @Autowired
    private OmsClient omsClient;

    @Autowired
    private TpSlEventPublisher eventPublisher;

    @Override
    @Transactional
    public Long createTpSlOrder(CreateTpSlRequest request) {
        // 1. 风控检查
        riskCheckService.checkBeforeCreate(request);

        // 2. 幂等性检查
        if (request.getClientOrderId() != null) {
            TpSlOrder existing = tpSlOrderMapper.selectByUserIdAndClientOrderId(
                request.getUserId(), request.getClientOrderId());
            if (existing != null) {
                log.warn("Duplicate TP/SL order: clientOrderId={}", request.getClientOrderId());
                return existing.getOrderId();
            }
        }

        // 3. 构建订单对象
        TpSlOrder order = new TpSlOrder();
        order.setOrderId(generateOrderId());
        order.setUserId(request.getUserId());
        order.setSymbol(request.getSymbol());
        order.setClientOrderId(request.getClientOrderId());
        order.setPositionId(request.getPositionId());

        order.setOrderType(request.getType());
        order.setTriggerType(request.getTriggerBy());
        order.setTriggerPrice(request.getTriggerPrice());

        // 根据持仓方向判断平仓方向 (这里简化，实际需要查询持仓方向)
        // TODO: 调用 position-service 获取持仓方向
        order.setTriggerSide("LONG"); // 暂时写死，后续需要查询

        order.setExecType(request.getExecType());
        order.setExecPrice(request.getExecPrice());
        order.setQuantity(request.getQuantity());

        // 移动止损参数
        if ("TRAILING".equals(request.getType())) {
            order.setTrailingPercent(request.getTrailingPercent());
            order.setTrailingOffset(request.getTrailingOffset());
            // 初始化最高/低价 (需要获取当前市场价格)
            order.setHighestPrice(request.getTriggerPrice());
        }

        order.setStatus("ACTIVE");
        order.setExpireTime(request.getExpireTime());
        order.setCreateTime(System.currentTimeMillis());
        order.setUpdateTime(System.currentTimeMillis());

        // 4. 保存订单
        tpSlOrderMapper.insert(order);

        log.info("Created TP/SL order: orderId={}, userId={}, symbol={}, type={}",
            order.getOrderId(), order.getUserId(), order.getSymbol(), order.getOrderType());

        return order.getOrderId();
    }

    @Override
    @Transactional
    public boolean modifyTpSlOrder(ModifyTpSlRequest request) {
        TpSlOrder order = tpSlOrderMapper.selectById(request.getTpSlOrderId());
        if (order == null) {
            throw new TpSlException("TP/SL订单不存在");
        }

        if (!order.getUserId().equals(request.getUserId())) {
            throw new TpSlException("无权修改该订单");
        }

        if (!"ACTIVE".equals(order.getStatus())) {
            throw new TpSlException("只能修改ACTIVE状态的订单");
        }

        // 更新字段
        if (request.getTriggerPrice() != null) {
            order.setTriggerPrice(request.getTriggerPrice());
        }
        if (request.getQuantity() != null) {
            order.setQuantity(request.getQuantity());
        }
        if (request.getExecPrice() != null) {
            order.setExecPrice(request.getExecPrice());
        }

        order.setUpdateTime(System.currentTimeMillis());
        tpSlOrderMapper.updateById(order);

        log.info("Modified TP/SL order: orderId={}", order.getOrderId());
        return true;
    }

    @Override
    @Transactional
    public boolean cancelTpSlOrder(Long orderId) {
        TpSlOrder order = tpSlOrderMapper.selectById(orderId);
        if (order == null) {
            throw new TpSlException("TP/SL订单不存在");
        }

        if (!"ACTIVE".equals(order.getStatus()) && !"PENDING".equals(order.getStatus())) {
            throw new TpSlException("只能撤销ACTIVE或PENDING状态的订单");
        }

        order.setStatus("CANCELLED");
        order.setUpdateTime(System.currentTimeMillis());
        tpSlOrderMapper.updateById(order);

        // 发布撤销事件
        eventPublisher.publishCancelled(order.getOrderId(), order.getUserId(),
            order.getSymbol(), "Manual cancel");

        log.info("Cancelled TP/SL order: orderId={}", orderId);
        return true;
    }

    @Override
    @Transactional
    public int cancelBatch(Long userId, String symbol, Long positionId) {
        List<TpSlOrder> orders;

        if (positionId != null) {
            // 撤销指定持仓的所有TP/SL
            orders = tpSlOrderMapper.selectActiveByPositionId(positionId);
        } else if (symbol != null) {
            // 撤销指定symbol的所有TP/SL
            orders = tpSlOrderMapper.selectByUserAndSymbol(userId, symbol, "ACTIVE");
        } else {
            // 撤销用户所有TP/SL
            orders = tpSlOrderMapper.selectByUserAndSymbol(userId, null, "ACTIVE");
        }

        int count = 0;
        for (TpSlOrder order : orders) {
            order.setStatus("CANCELLED");
            order.setUpdateTime(System.currentTimeMillis());
            tpSlOrderMapper.updateById(order);

            eventPublisher.publishCancelled(order.getOrderId(), order.getUserId(),
                order.getSymbol(), "Batch cancel");
            count++;
        }

        log.info("Batch cancelled TP/SL orders: userId={}, symbol={}, count={}",
            userId, symbol, count);
        return count;
    }

    @Override
    public PositionTpSlVO getByPosition(Long positionId) {
        List<TpSlOrder> orders = tpSlOrderMapper.selectByPositionId(positionId);

        PositionTpSlVO vo = new PositionTpSlVO();
        vo.setPositionId(positionId);

        for (TpSlOrder order : orders) {
            PositionTpSlVO.TpSlInfo info = new PositionTpSlVO.TpSlInfo();
            info.setTpSlOrderId(order.getOrderId());
            info.setTriggerPrice(order.getTriggerPrice());
            info.setQuantity(order.getQuantity());
            info.setExecType(order.getExecType());
            info.setStatus(order.getStatus());

            if ("TP".equals(order.getOrderType())) {
                vo.setTakeProfit(info);
            } else if ("SL".equals(order.getOrderType())) {
                vo.setStopLoss(info);
            }
        }

        return vo;
    }

    @Override
    public List<TpSlOrderVO> listOrders(Long userId, String symbol, String status) {
        List<TpSlOrder> orders = tpSlOrderMapper.selectByUserAndSymbol(userId, symbol, status);
        return orders.stream().map(this::convertToVO).collect(Collectors.toList());
    }

    @Override
    public TpSlOrderVO getOrderDetail(Long orderId) {
        TpSlOrder order = tpSlOrderMapper.selectById(orderId);
        if (order == null) {
            throw new TpSlException("TP/SL订单不存在");
        }
        return convertToVO(order);
    }

    @Override
    public void checkAndTrigger(String symbol, Long markPrice) {
        // 查询该symbol下所有ACTIVE订单
        List<TpSlOrder> activeOrders = tpSlOrderMapper.selectActiveBySymbol(symbol);

        log.debug("Checking TP/SL orders: symbol={}, markPrice={}, activeCount={}",
            symbol, markPrice, activeOrders.size());

        for (TpSlOrder order : activeOrders) {
            try {
                if (checkTriggerCondition(order, markPrice)) {
                    triggerOrder(order, markPrice);
                }
            } catch (Exception e) {
                log.error("Failed to check TP/SL order: orderId={}", order.getOrderId(), e);
            }
        }
    }

    @Override
    @Transactional
    public void cancelByPositionClose(Long positionId) {
        List<TpSlOrder> orders = tpSlOrderMapper.selectActiveByPositionId(positionId);

        for (TpSlOrder order : orders) {
            order.setStatus("CANCELLED");
            order.setUpdateTime(System.currentTimeMillis());
            tpSlOrderMapper.updateById(order);

            eventPublisher.publishCancelled(order.getOrderId(), order.getUserId(),
                order.getSymbol(), "Position closed");

            log.info("Auto cancelled TP/SL due to position close: orderId={}, positionId={}",
                order.getOrderId(), positionId);
        }
    }

    @Override
    public void updateTrailingStop(String symbol, Long markPrice) {
        List<TpSlOrder> trailingOrders = tpSlOrderMapper.selectTrailingBySymbol(symbol);

        for (TpSlOrder order : trailingOrders) {
            try {
                updateTrailingPrice(order, markPrice);
            } catch (Exception e) {
                log.error("Failed to update trailing stop: orderId={}", order.getOrderId(), e);
            }
        }
    }

    /**
     * 检查触发条件
     */
    private boolean checkTriggerCondition(TpSlOrder order, Long markPrice) {
        String orderType = order.getOrderType();
        String triggerSide = order.getTriggerSide();
        Long triggerPrice = order.getTriggerPrice();

        // TP止盈逻辑
        if ("TP".equals(orderType)) {
            if ("LONG".equals(triggerSide)) {
                // 多单止盈: 标记价格 >= 触发价格
                return markPrice >= triggerPrice;
            } else {
                // 空单止盈: 标记价格 <= 触发价格
                return markPrice <= triggerPrice;
            }
        }
        // SL止损逻辑
        else if ("SL".equals(orderType)) {
            if ("LONG".equals(triggerSide)) {
                // 多单止损: 标记价格 <= 触发价格
                return markPrice <= triggerPrice;
            } else {
                // 空单止损: 标记价格 >= 触发价格
                return markPrice >= triggerPrice;
            }
        }
        // TRAILING移动止损逻辑
        else if ("TRAILING".equals(orderType)) {
            return checkTrailingTrigger(order, markPrice);
        }

        return false;
    }

    /**
     * 检查移动止损触发条件
     */
    private boolean checkTrailingTrigger(TpSlOrder order, Long markPrice) {
        Long highestPrice = order.getHighestPrice();
        if (highestPrice == null) {
            return false;
        }

        String triggerSide = order.getTriggerSide();
        Long trailingPercent = order.getTrailingPercent();

        if ("LONG".equals(triggerSide)) {
            // 多单移动止损: 从最高价回调
            long callbackAmount = highestPrice * trailingPercent / 10000;
            long callbackPrice = highestPrice - callbackAmount;
            return markPrice <= callbackPrice;
        } else {
            // 空单移动止损: 从最低价回升
            long callbackAmount = highestPrice * trailingPercent / 10000;
            long callbackPrice = highestPrice + callbackAmount;
            return markPrice >= callbackPrice;
        }
    }

    /**
     * 触发订单
     */
    @Transactional
    public void triggerOrder(TpSlOrder order, Long markPrice) {
        // 1. 触发前风控检查
        riskCheckService.checkBeforeTrigger(order.getUserId(), order.getPositionId(), order.getQuantity());

        // 2. 更新订单状态为 TRIGGERED
        order.setStatus("TRIGGERED");
        order.setTriggerTime(System.currentTimeMillis());
        order.setTriggeredTime(System.currentTimeMillis());
        order.setTriggeredPrice(markPrice);
        order.setUpdateTime(System.currentTimeMillis());
        tpSlOrderMapper.updateById(order);

        log.info("TP/SL order triggered: orderId={}, symbol={}, type={}, triggerPrice={}, markPrice={}",
            order.getOrderId(), order.getSymbol(), order.getOrderType(),
            order.getTriggerPrice(), markPrice);

        // 3. 创建平仓订单
        try {
            String closeSide = getCloseSide(order.getTriggerSide());
            Long closeOrderId = omsClient.createCloseOrder(
                order.getUserId(),
                order.getSymbol(),
                closeSide,
                order.getExecType(),
                order.getQuantity(),
                order.getExecPrice(),
                order.getPositionId()
            );

            // 4. 更新执行订单ID
            order.setExecOrderId(closeOrderId);
            order.setStatus("EXECUTED");
            order.setExecResult("SUCCESS");
            tpSlOrderMapper.updateById(order);

            // 5. 记录执行日志
            saveExecLog(order, markPrice, closeOrderId, "SUCCESS", null);

            // 6. 发布触发事件
            eventPublisher.publishTriggered(
                order.getOrderId(),
                order.getUserId(),
                order.getSymbol(),
                order.getOrderType(),
                order.getTriggerPrice(),
                order.getQuantity(),
                order.getExecType(),
                order.getExecPrice(),
                order.getTriggerSide()
            );

            log.info("TP/SL order executed successfully: orderId={}, closeOrderId={}",
                order.getOrderId(), closeOrderId);

        } catch (Exception e) {
            // 执行失败，记录日志
            log.error("Failed to execute TP/SL order: orderId={}", order.getOrderId(), e);

            order.setStatus("TRIGGERED");
            order.setExecResult("FAIL");
            tpSlOrderMapper.updateById(order);

            saveExecLog(order, markPrice, null, "FAIL", e.getMessage());

            // TODO: 重试机制
        }
    }

    /**
     * 更新移动止损价格
     */
    private void updateTrailingPrice(TpSlOrder order, Long markPrice) {
        String triggerSide = order.getTriggerSide();
        Long highestPrice = order.getHighestPrice();

        if ("LONG".equals(triggerSide)) {
            // 多单: 更新最高价
            if (highestPrice == null || markPrice > highestPrice) {
                order.setHighestPrice(markPrice);

                // 计算新触发价
                Long trailingPercent = order.getTrailingPercent();
                long callbackAmount = markPrice * trailingPercent / 10000;
                long newTriggerPrice = markPrice - callbackAmount;

                order.setTriggerPrice(newTriggerPrice);
                order.setUpdateTime(System.currentTimeMillis());
                tpSlOrderMapper.updateById(order);

                log.debug("Updated trailing stop: orderId={}, newHighest={}, newTrigger={}",
                    order.getOrderId(), markPrice, newTriggerPrice);
            }
        } else {
            // 空单: 更新最低价
            if (highestPrice == null || markPrice < highestPrice) {
                order.setHighestPrice(markPrice);

                // 计算新触发价
                Long trailingPercent = order.getTrailingPercent();
                long callbackAmount = markPrice * trailingPercent / 10000;
                long newTriggerPrice = markPrice + callbackAmount;

                order.setTriggerPrice(newTriggerPrice);
                order.setUpdateTime(System.currentTimeMillis());
                tpSlOrderMapper.updateById(order);

                log.debug("Updated trailing stop: orderId={}, newLowest={}, newTrigger={}",
                    order.getOrderId(), markPrice, newTriggerPrice);
            }
        }
    }

    /**
     * 保存执行日志
     */
    private void saveExecLog(TpSlOrder order, Long markPrice, Long execOrderId,
                             String execResult, String errorMsg) {
        TpSlExecLog log = new TpSlExecLog();
        log.setTpSlOrderId(order.getOrderId());
        log.setUserId(order.getUserId());
        log.setSymbol(order.getSymbol());
        log.setTriggerPrice(order.getTriggerPrice());
        log.setMarkPrice(markPrice);
        log.setExecType(order.getExecType());
        log.setQuantity(order.getQuantity());
        log.setExecOrderId(execOrderId);
        log.setExecResult(execResult);
        log.setErrorMsg(errorMsg);
        log.setCreateTime(System.currentTimeMillis());

        execLogMapper.insert(log);
    }

    /**
     * 获取平仓方向
     */
    private String getCloseSide(String triggerSide) {
        // LONG持仓 → SELL平仓
        // SHORT持仓 → BUY平仓
        return "LONG".equals(triggerSide) ? "SELL" : "BUY";
    }

    /**
     * 转换为VO
     */
    private TpSlOrderVO convertToVO(TpSlOrder order) {
        TpSlOrderVO vo = new TpSlOrderVO();
        BeanUtils.copyProperties(order, vo);
        vo.setTpSlOrderId(order.getOrderId());
        vo.setType(order.getOrderType());
        vo.setCreatedAt(order.getCreateTime());
        vo.setUpdatedAt(order.getUpdateTime());
        return vo;
    }

    /**
     * 生成订单ID
     */
    private Long generateOrderId() {
        // 使用雪花算法或其他ID生成策略
        return System.currentTimeMillis();
    }
}
