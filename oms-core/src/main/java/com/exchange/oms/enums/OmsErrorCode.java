package com.exchange.oms.enums;

/**
 * 错误码枚举
 */
public enum OmsErrorCode {
    
    // 1xxx - 幂等相关
    OMS_1001("OMS_1001", "重复的clientOrderId"),
    OMS_1002("OMS_1002", "幂等key冲突，参数不一致"),
    
    // 2xxx - 订单相关
    OMS_2001("OMS_2001", "订单不存在"),
    OMS_2002("OMS_2002", "订单状态不允许撤单"),
    OMS_2003("OMS_2003", "订单已终态"),
    
    // 3xxx - 风控相关
    OMS_3001("OMS_3001", "风控拒绝"),
    OMS_3002("OMS_3002", "资金冻结失败"),
    
    // 4xxx - 参数校验
    OMS_4001("OMS_4001", "参数校验失败"),
    OMS_4002("OMS_4002", "价格不合法"),
    OMS_4003("OMS_4003", "数量不合法"),
    
    // 9xxx - 系统异常
    OMS_9001("OMS_9001", "系统异常"),
    OMS_9002("OMS_9002", "数据库异常"),
    OMS_9003("OMS_9003", "乐观锁冲突");
    
    private final String code;
    private final String message;
    
    OmsErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }
    
    public String getCode() {
        return code;
    }
    
    public String getMessage() {
        return message;
    }
}



