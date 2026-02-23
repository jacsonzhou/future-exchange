package com.exchange.margin.producer;

import com.exchange.margin.dto.MarginChangeEvent;
import com.exchange.margin.entity.PositionMarginDetail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 保证金变动事件生产者
 *
 * 发布到 margin-change-topic，供其他服务消费
 */
@Slf4j
@Component
public class MarginChangeProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    // 事件序列号生成器
    private final AtomicLong sequenceGenerator = new AtomicLong(0);

    // Topic名称
    private static final String MARGIN_CHANGE_TOPIC = "margin-change-topic";

    public MarginChangeProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * 发布开仓事件
     *
     * @param position 仓位详情
     */
    public void publishOpenEvent(PositionMarginDetail position) {
        MarginChangeEvent event = buildBaseEvent(position, "OPEN");
        event.setChangeAmount(position.getIsolatedMargin());
        event.setBeforeMargin(0L);
        event.setAfterMargin(position.getIsolatedMargin());
        event.setRemark(position.isIsolated() ? "逐仓开仓" : "全仓开仓");

        publishEvent(event);
    }

    /**
     * 发布平仓事件
     *
     * @param position 仓位详情
     * @param beforeMargin 平仓前保证金
     */
    public void publishCloseEvent(PositionMarginDetail position, Long beforeMargin) {
        MarginChangeEvent event = buildBaseEvent(position, "CLOSE");
        event.setChangeAmount(-beforeMargin);
        event.setBeforeMargin(beforeMargin);
        event.setAfterMargin(0L);
        event.setRemark(position.isIsolated() ? "逐仓平仓" : "全仓平仓");

        publishEvent(event);
    }

    /**
     * 发布追加保证金事件
     *
     * @param position 仓位详情
     * @param beforeMargin 追加前保证金
     * @param addAmount 追加金额
     */
    public void publishAddMarginEvent(PositionMarginDetail position, Long beforeMargin, Long addAmount) {
        MarginChangeEvent event = buildBaseEvent(position, "ADD");
        event.setChangeAmount(addAmount);
        event.setBeforeMargin(beforeMargin);
        event.setAfterMargin(position.getIsolatedMargin());
        event.setRemark("追加逐仓保证金");

        publishEvent(event);
    }

    /**
     * 发布减少保证金事件
     *
     * @param position 仓位详情
     * @param beforeMargin 减少前保证金
     * @param reduceAmount 减少金额
     */
    public void publishReduceMarginEvent(PositionMarginDetail position, Long beforeMargin, Long reduceAmount) {
        MarginChangeEvent event = buildBaseEvent(position, "REDUCE");
        event.setChangeAmount(-reduceAmount);
        event.setBeforeMargin(beforeMargin);
        event.setAfterMargin(position.getIsolatedMargin());
        event.setRemark("减少逐仓保证金");

        publishEvent(event);
    }

    /**
     * 发布模式切换事件
     *
     * @param position 仓位详情
     * @param beforeMode 切换前模式
     * @param beforeMargin 切换前保证金
     * @param afterMargin 切换后保证金
     */
    public void publishSwitchEvent(PositionMarginDetail position, String beforeMode,
                                   Long beforeMargin, Long afterMargin) {
        MarginChangeEvent event = buildBaseEvent(position, "SWITCH");
        event.setChangeAmount(afterMargin - beforeMargin);
        event.setBeforeMargin(beforeMargin);
        event.setAfterMargin(afterMargin);

        if ("ISOLATED".equals(beforeMode) && "CROSS".equals(position.getMarginMode())) {
            event.setRemark("逐仓转全仓");
        } else if ("CROSS".equals(beforeMode) && "ISOLATED".equals(position.getMarginMode())) {
            event.setRemark("全仓转逐仓");
        } else {
            event.setRemark("保证金模式切换");
        }

        publishEvent(event);
    }

    /**
     * 发布调整杠杆事件
     *
     * @param position 仓位详情
     * @param beforeLeverage 调整前杠杆
     * @param beforeMargin 调整前保证金
     * @param beforeLiqPrice 调整前强平价
     * @param beforeMarginRatio 调整前保证金率
     */
    public void publishLeverageAdjustEvent(PositionMarginDetail position, Integer beforeLeverage,
                                           Long beforeMargin, Long beforeLiqPrice, Long beforeMarginRatio) {
        MarginChangeEvent event = buildBaseEvent(position, "LEVERAGE_ADJUST");
        event.setChangeAmount(position.getPositionMargin() - beforeMargin);
        event.setBeforeMargin(beforeMargin);
        event.setAfterMargin(position.getPositionMargin());
        event.setBeforeLeverage(beforeLeverage);
        event.setAfterLeverage(position.getLeverage());
        event.setBeforeLiquidationPrice(beforeLiqPrice);
        event.setAfterLiquidationPrice(position.getLiquidationPrice());
        event.setBeforeMarginRatio(beforeMarginRatio);
        event.setAfterMarginRatio(position.getMarginRatio());
        event.setRemark(String.format("调整杠杆：%dx -> %dx", beforeLeverage, position.getLeverage()));

        publishEvent(event);
    }

    /**
     * 发布强平事件
     *
     * @param position 仓位详情
     * @param beforeMargin 强平前保证金
     */
    public void publishLiquidationEvent(PositionMarginDetail position, Long beforeMargin) {
        MarginChangeEvent event = buildBaseEvent(position, "LIQUIDATION");
        event.setChangeAmount(-beforeMargin);
        event.setBeforeMargin(beforeMargin);
        event.setAfterMargin(0L);
        event.setRemark("强制平仓");

        publishEvent(event);
    }

    /**
     * 发布ADL事件
     *
     * @param position 仓位详情
     * @param beforeMargin ADL前保证金
     * @param afterMargin ADL后保证金
     */
    public void publishAdlEvent(PositionMarginDetail position, Long beforeMargin, Long afterMargin) {
        MarginChangeEvent event = buildBaseEvent(position, "ADL");
        event.setChangeAmount(afterMargin - beforeMargin);
        event.setBeforeMargin(beforeMargin);
        event.setAfterMargin(afterMargin);
        event.setRemark("自动减仓");

        publishEvent(event);
    }

    /**
     * 构建基础事件对象
     *
     * @param position 仓位详情
     * @param changeType 变动类型
     * @return 保证金变动事件
     */
    private MarginChangeEvent buildBaseEvent(PositionMarginDetail position, String changeType) {
        MarginChangeEvent event = new MarginChangeEvent();

        event.setUserId(position.getUserId());
        event.setPositionId(position.getPositionId());
        event.setSymbol(position.getSymbol());
        event.setMarginMode(position.getMarginMode());
        event.setChangeType(changeType);

        event.setAfterLeverage(position.getLeverage());
        event.setAfterMarginRatio(position.getMarginRatio());
        event.setAfterLiquidationPrice(position.getLiquidationPrice());

        event.setTimestamp(System.currentTimeMillis());
        event.setSequence(sequenceGenerator.incrementAndGet());

        return event;
    }

    /**
     * 发布事件到Kafka
     *
     * @param event 保证金变动事件
     */
    private void publishEvent(MarginChangeEvent event) {
        try {
            kafkaTemplate.send(MARGIN_CHANGE_TOPIC, event.getUserId().toString(), event);

            log.info("[MarginChangeProducer] Published margin change event: userId={}, positionId={}, " +
                            "symbol={}, changeType={}, changeAmount={}, marginMode={}, sequence={}",
                    event.getUserId(), event.getPositionId(), event.getSymbol(),
                    event.getChangeType(), event.getChangeAmount(), event.getMarginMode(),
                    event.getSequence());

        } catch (Exception e) {
            log.error("[MarginChangeProducer] Failed to publish margin change event: userId={}, " +
                            "positionId={}, changeType={}",
                    event.getUserId(), event.getPositionId(), event.getChangeType(), e);
        }
    }
}
