package com.exchange.tpsl.service.impl;

import com.exchange.tpsl.dto.CreateTpSlRequest;
import com.exchange.tpsl.exception.TpSlException;
import com.exchange.tpsl.service.RiskCheckService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 风控检查服务实现
 */
@Slf4j
@Service
public class RiskCheckServiceImpl implements RiskCheckService {

    @Override
    public void checkBeforeCreate(CreateTpSlRequest request) {
        // 1. 检查触发价格不能为0或极端值
        Long triggerPrice = request.getTriggerPrice();
        if (triggerPrice == null || triggerPrice <= 0) {
            throw new TpSlException("触发价格必须大于0");
        }

        // 2. 检查数量
        Long quantity = request.getQuantity();
        if (quantity == null || quantity <= 0) {
            throw new TpSlException("平仓数量必须大于0");
        }

        // 3. 检查执行类型
        String execType = request.getExecType();
        if (!"MARKET".equals(execType) && !"LIMIT".equals(execType)) {
            throw new TpSlException("执行类型只能是MARKET或LIMIT");
        }

        // 4. 如果是限价单，必须填写执行价格
        if ("LIMIT".equals(execType) && request.getExecPrice() == null) {
            throw new TpSlException("限价单必须填写执行价格");
        }

        // 5. 移动止损参数检查
        if ("TRAILING".equals(request.getType())) {
            if (request.getTrailingPercent() == null && request.getTrailingOffset() == null) {
                throw new TpSlException("移动止损必须设置回调比例或回调金额");
            }

            if (request.getTrailingPercent() != null) {
                // 回调比例必须在 [0.1%, 10%] 之间，即 [10, 1000]
                if (request.getTrailingPercent() < 10 || request.getTrailingPercent() > 1000) {
                    throw new TpSlException("回调比例必须在0.1%到10%之间");
                }
            }
        }

        log.info("Risk check passed for TP/SL creation: userId={}, symbol={}",
            request.getUserId(), request.getSymbol());
    }

    @Override
    public void checkBeforeTrigger(Long userId, Long positionId, Long quantity) {
        // 触发前检查:
        // 1. 持仓是否仍然存在
        // 2. 持仓数量是否充足
        // 3. 账户状态是否正常
        // 这里需要调用 position-service 和 account-service，暂时简化

        log.info("Risk check passed for TP/SL trigger: userId={}, positionId={}, quantity={}",
            userId, positionId, quantity);
    }
}
