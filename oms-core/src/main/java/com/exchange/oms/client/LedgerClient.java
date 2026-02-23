package com.exchange.oms.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.math.BigDecimal;

/**
 * Ledger 服务客户端
 * 
 * 用于调用 Ledger 服务的冻结/解冻接口
 */
@FeignClient(name = "ledger-core", path = "/internal/ledger")
public interface LedgerClient {
    
    /**
     * 冻结保证金（下单时）
     * 
     * @param request 冻结请求
     */
    @PostMapping("/freeze")
    void freezeMargin(@RequestBody FreezeRequest request);
    
    /**
     * 解冻保证金（撤单时）
     * 
     * @param request 解冻请求
     */
    @PostMapping("/unfreeze")
    void unfreezeMargin(@RequestBody UnfreezeRequest request);
    
    /**
     * 冻结请求
     */
    class FreezeRequest {
        private Long userId;
        private String currency;
        private BigDecimal amount;
        private Long orderId;
        
        public Long getUserId() {
            return userId;
        }
        
        public void setUserId(Long userId) {
            this.userId = userId;
        }
        
        public String getCurrency() {
            return currency;
        }
        
        public void setCurrency(String currency) {
            this.currency = currency;
        }
        
        public BigDecimal getAmount() {
            return amount;
        }
        
        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }
        
        public Long getOrderId() {
            return orderId;
        }
        
        public void setOrderId(Long orderId) {
            this.orderId = orderId;
        }
    }
    
    /**
     * 解冻请求
     */
    class UnfreezeRequest {
        private Long userId;
        private String currency;
        private BigDecimal amount;
        private Long orderId;
        
        public Long getUserId() {
            return userId;
        }
        
        public void setUserId(Long userId) {
            this.userId = userId;
        }
        
        public String getCurrency() {
            return currency;
        }
        
        public void setCurrency(String currency) {
            this.currency = currency;
        }
        
        public BigDecimal getAmount() {
            return amount;
        }
        
        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }
        
        public Long getOrderId() {
            return orderId;
        }
        
        public void setOrderId(Long orderId) {
            this.orderId = orderId;
        }
    }
}

