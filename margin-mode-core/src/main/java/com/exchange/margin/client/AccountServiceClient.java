package com.exchange.margin.client;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 账户服务客户端
 *
 * 用于调用 Account Service 查询账户余额等信息
 */
@Slf4j
@Component
public class AccountServiceClient {

    @Value("${service.account.url:http://localhost:9090}")
    private String accountServiceUrl;

    private final RestTemplate restTemplate;

    public AccountServiceClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 查询账户余额
     *
     * @param userId 用户ID
     * @return 账户余额信息
     */
    public AccountBalance getAccountBalance(Long userId) {
        try {
            String url = accountServiceUrl + "/internal/account/" + userId + "/balance";
            return restTemplate.getForObject(url, AccountBalance.class);
        } catch (Exception e) {
            log.error("[AccountServiceClient] Failed to get account balance, userId={}", userId, e);
            return null;
        }
    }

    /**
     * 账户余额DTO
     */
    @Data
    public static class AccountBalance {
        private Long userId;
        private Long walletBalance;          // 钱包余额
        private Long availableBalance;       // 可用余额
        private Long frozenBalance;          // 冻结余额（挂单冻结）
        private Long totalUnrealizedPnl;     // 总未实现盈亏（全仓）
        private Long todayRealizedPnl;       // 今日已实现盈亏
    }
}
