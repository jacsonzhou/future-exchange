package com.exchange.position.enums;

import lombok.Getter;

/**
 * Risk Event Type 风险事件类型
 */
@Getter
public enum RiskEventType {
    
    MARGIN_WARNING("MARGIN_WARNING", "保证金预警"),
    LIQUIDATION_ALERT("LIQUIDATION_ALERT", "强平告警"),
    POSITION_CLOSE("POSITION_CLOSE", "持仓平仓");
    
    private final String code;
    private final String description;
    
    RiskEventType(String code, String description) {
        this.code = code;
        this.description = description;
    }
}



