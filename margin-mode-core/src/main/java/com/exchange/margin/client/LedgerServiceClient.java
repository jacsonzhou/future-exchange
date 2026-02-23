package com.exchange.margin.client;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 账本服务客户端
 *
 * 用于调用 Ledger Service 进行保证金变动记账
 */
@Slf4j
@Component
public class LedgerServiceClient {

    @Value("${service.ledger.url:http://localhost:8085}")
    private String ledgerServiceUrl;

    private final RestTemplate restTemplate;

    public LedgerServiceClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 记录保证金变动
     *
     * @param request 记账请求
     * @return 记账是否成功
     */
    public boolean recordMarginChange(MarginChangeRequest request) {
        try {
            String url = ledgerServiceUrl + "/internal/ledger/margin-change";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<MarginChangeRequest> entity = new HttpEntity<>(request, headers);

            LedgerResponse response = restTemplate.postForObject(url, entity, LedgerResponse.class);

            if (response != null && response.isSuccess()) {
                log.info("[LedgerServiceClient] Margin change recorded, userId={}, positionId={}, " +
                                "changeType={}, amount={}",
                        request.getUserId(), request.getPositionId(),
                        request.getChangeType(), request.getAmount());
                return true;
            } else {
                log.warn("[LedgerServiceClient] Failed to record margin change, response={}",
                        response);
                return false;
            }
        } catch (Exception e) {
            log.error("[LedgerServiceClient] Failed to record margin change, request={}",
                    request, e);
            return false;
        }
    }

    /**
     * 记录逐仓开仓
     *
     * @param userId 用户ID
     * @param positionId 仓位ID
     * @param symbol 交易对
     * @param isolatedMargin 逐仓保证金
     * @return 是否成功
     */
    public boolean recordIsolatedOpen(Long userId, Long positionId, String symbol,
                                      Long isolatedMargin) {
        MarginChangeRequest request = new MarginChangeRequest();
        request.setUserId(userId);
        request.setPositionId(positionId);
        request.setSymbol(symbol);
        request.setChangeType("ISOLATED_MARGIN_OPEN");
        request.setMarginMode("ISOLATED");
        request.setAmount(isolatedMargin);
        request.setDebitAccount("逐仓保证金");
        request.setCreditAccount("可用余额");
        request.setRemark("逐仓开仓");

        return recordMarginChange(request);
    }

    /**
     * 记录逐仓平仓
     *
     * @param userId 用户ID
     * @param positionId 仓位ID
     * @param symbol 交易对
     * @param isolatedMargin 逐仓保证金
     * @param realizedPnl 已实现盈亏
     * @return 是否成功
     */
    public boolean recordIsolatedClose(Long userId, Long positionId, String symbol,
                                       Long isolatedMargin, Long realizedPnl) {
        MarginChangeRequest request = new MarginChangeRequest();
        request.setUserId(userId);
        request.setPositionId(positionId);
        request.setSymbol(symbol);
        request.setChangeType("ISOLATED_POSITION_CLOSE");
        request.setMarginMode("ISOLATED");
        request.setAmount(isolatedMargin + realizedPnl);
        request.setDebitAccount("可用余额");
        request.setCreditAccount("逐仓保证金");
        request.setRemark("逐仓平仓，保证金归还+盈亏");

        return recordMarginChange(request);
    }

    /**
     * 记录追加逐仓保证金
     *
     * @param userId 用户ID
     * @param positionId 仓位ID
     * @param symbol 交易对
     * @param amount 追加金额
     * @return 是否成功
     */
    public boolean recordAddIsolatedMargin(Long userId, Long positionId, String symbol,
                                           Long amount) {
        MarginChangeRequest request = new MarginChangeRequest();
        request.setUserId(userId);
        request.setPositionId(positionId);
        request.setSymbol(symbol);
        request.setChangeType("ISOLATED_MARGIN_ADD");
        request.setMarginMode("ISOLATED");
        request.setAmount(amount);
        request.setDebitAccount("逐仓保证金");
        request.setCreditAccount("可用余额");
        request.setRemark("追加逐仓保证金");

        return recordMarginChange(request);
    }

    /**
     * 记录减少逐仓保证金
     *
     * @param userId 用户ID
     * @param positionId 仓位ID
     * @param symbol 交易对
     * @param amount 减少金额
     * @return 是否成功
     */
    public boolean recordReduceIsolatedMargin(Long userId, Long positionId, String symbol,
                                              Long amount) {
        MarginChangeRequest request = new MarginChangeRequest();
        request.setUserId(userId);
        request.setPositionId(positionId);
        request.setSymbol(symbol);
        request.setChangeType("ISOLATED_MARGIN_REMOVE");
        request.setMarginMode("ISOLATED");
        request.setAmount(amount);
        request.setDebitAccount("可用余额");
        request.setCreditAccount("逐仓保证金");
        request.setRemark("减少逐仓保证金");

        return recordMarginChange(request);
    }

    /**
     * 记录模式切换（逐仓转全仓）
     *
     * @param userId 用户ID
     * @param positionId 仓位ID
     * @param symbol 交易对
     * @param isolatedMargin 归还的逐仓保证金
     * @return 是否成功
     */
    public boolean recordSwitchToC ross(Long userId, Long positionId, String symbol,
                                        Long isolatedMargin) {
        MarginChangeRequest request = new MarginChangeRequest();
        request.setUserId(userId);
        request.setPositionId(positionId);
        request.setSymbol(symbol);
        request.setChangeType("SWITCH_TO_CROSS");
        request.setMarginMode("CROSS");
        request.setAmount(isolatedMargin);
        request.setDebitAccount("可用余额");
        request.setCreditAccount("逐仓保证金");
        request.setRemark("逐仓转全仓");

        return recordMarginChange(request);
    }

    /**
     * 记录模式切换（全仓转逐仓）
     *
     * @param userId 用户ID
     * @param positionId 仓位ID
     * @param symbol 交易对
     * @param isolatedMargin 分配的逐仓保证金
     * @return 是否成功
     */
    public boolean recordSwitchToIsolated(Long userId, Long positionId, String symbol,
                                          Long isolatedMargin) {
        MarginChangeRequest request = new MarginChangeRequest();
        request.setUserId(userId);
        request.setPositionId(positionId);
        request.setSymbol(symbol);
        request.setChangeType("SWITCH_TO_ISOLATED");
        request.setMarginMode("ISOLATED");
        request.setAmount(isolatedMargin);
        request.setDebitAccount("逐仓保证金");
        request.setCreditAccount("可用余额");
        request.setRemark("全仓转逐仓");

        return recordMarginChange(request);
    }

    // ==================== DTO ====================

    /**
     * 保证金变动记账请求
     */
    @Data
    public static class MarginChangeRequest {
        private Long userId;              // 用户ID
        private Long positionId;          // 仓位ID
        private String symbol;            // 交易对
        private String changeType;        // 变动类型
        private String marginMode;        // 保证金模式
        private Long amount;              // 金额
        private String debitAccount;      // 借方账户
        private String creditAccount;     // 贷方账户
        private String remark;            // 备注
    }

    /**
     * 账本响应
     */
    @Data
    public static class LedgerResponse {
        private boolean success;          // 是否成功
        private String message;           // 消息
        private Long ledgerEntryId;       // 账本分录ID
    }
}
