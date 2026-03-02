package com.exchange.liquidation.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 保险基金服务客户端
 * 
 * 调用保险基金服务，申请赔付穿仓损失
 */
@FeignClient(name = "adl-core", path = "/internal/insurance-fund")
public interface InsuranceFundClient {
    
    /**
     * 申请保险基金赔付
     * 
     * @param liquidationId 强平ID（作为bizSeq，保证幂等）
     * @param request 赔付请求
     * @return 赔付结果
     */
    @PostMapping("/expense")
    Map<String, Object> expense(
            @RequestParam("bizSeq") String liquidationId,
            @RequestBody InsuranceFundExpenseRequest request
    );

    /**
     * 强平盈余注资保险基金
     *
     * @param bizSeq 业务流水号（幂等）
     * @param request 注资请求
     * @return 注资结果
     */
    @PostMapping("/income")
    Map<String, Object> income(
            @RequestParam("bizSeq") String bizSeq,
            @RequestBody InsuranceFundIncomeRequest request
    );
    
    /**
     * 查询保险基金余额
     * 
     * @param symbol 交易对（可选）
     * @return 余额信息
     */
    @PostMapping("/balance")
    Map<String, Object> getBalance(@RequestParam(required = false) String symbol);
    
    /**
     * 保险基金赔付请求
     */
    class InsuranceFundExpenseRequest {
        private String symbol;
        private Long amount; // 赔付金额（8位小数）
        private String reason; // 赔付原因
        private Long userId;
        private Long positionId;
        
        // Getters and Setters
        public String getSymbol() { return symbol; }
        public void setSymbol(String symbol) { this.symbol = symbol; }
        
        public Long getAmount() { return amount; }
        public void setAmount(Long amount) { this.amount = amount; }
        
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        
        public Long getPositionId() { return positionId; }
        public void setPositionId(Long positionId) { this.positionId = positionId; }
    }

    /**
     * 保险基金注资请求
     */
    class InsuranceFundIncomeRequest {
        private String symbol;
        private Long amount; // 注资金额（8位小数）
        private String reason; // 注资原因
        private String changeType; // 变更类型

        public String getSymbol() { return symbol; }
        public void setSymbol(String symbol) { this.symbol = symbol; }

        public Long getAmount() { return amount; }
        public void setAmount(Long amount) { this.amount = amount; }

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }

        public String getChangeType() { return changeType; }
        public void setChangeType(String changeType) { this.changeType = changeType; }
    }
}
