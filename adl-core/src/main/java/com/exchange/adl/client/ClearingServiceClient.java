package com.exchange.adl.client;

import com.exchange.adl.client.dto.ClearingRequest;
import com.exchange.adl.client.dto.ClearingResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Clearing Service 客户端
 *
 * 职责：
 * 1. 提交ADL记账请求
 * 2. 处理记账结果
 * 3. 支持事务性和幂等性
 */
@Slf4j
@Component
public class ClearingServiceClient {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${service.clearing.url:}")
    private String clearingServiceUrl;

    @PostConstruct
    public void validateConfig() {
        if (clearingServiceUrl == null || clearingServiceUrl.isBlank()) {
            throw new IllegalStateException("Missing required config: service.clearing.url");
        }
    }

    /**
     * 提交ADL记账请求
     *
     * @param adlExecutionId ADL执行ID（幂等性标识）
     * @param targetUserId 被ADL用户ID
     * @param targetPositionId 被ADL持仓ID
     * @param sourceUserId 触发ADL用户ID
     * @param symbol 交易对
     * @param side 被ADL方向
     * @param adlPrice ADL价格
     * @param adlQty ADL数量
     * @param targetPnlChange 被ADL用户盈亏变化
     * @return 记账结果
     */
    public ClearingResponse submitAdlClearing(
            String adlExecutionId,
            Long targetUserId,
            Long targetPositionId,
            Long sourceUserId,
            String symbol,
            String side,
            BigDecimal adlPrice,
            BigDecimal adlQty,
            BigDecimal targetPnlChange) {

        try {
            log.info("Submitting ADL clearing: adlExecutionId={}, targetUserId={}, adlQty={}",
                    adlExecutionId, targetUserId, adlQty);

            // 构造记账请求
            ClearingRequest request = buildAdlClearingRequest(
                    adlExecutionId, targetUserId, targetPositionId, sourceUserId,
                    symbol, side, adlPrice, adlQty, targetPnlChange
            );

            // 调用Clearing Service
            String url = clearingServiceUrl + "/internal/clearing/adl";
            Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);

            // 解析响应
            if (response != null && (Integer) response.get("code") == 0) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");

                ClearingResponse clearingResponse = new ClearingResponse();
                clearingResponse.setSuccess(true);
                clearingResponse.setBizSeq(getString(data, "bizSeq"));
                clearingResponse.setLedgerIds((List<String>) data.get("ledgerIds"));
                clearingResponse.setMessage("ADL记账成功");

                log.info("ADL clearing succeeded: adlExecutionId={}, bizSeq={}",
                        adlExecutionId, clearingResponse.getBizSeq());

                return clearingResponse;

            } else {
                String message = response != null ? (String) response.get("message") : "Unknown error";
                log.error("ADL clearing failed: adlExecutionId={}, message={}", adlExecutionId, message);

                ClearingResponse clearingResponse = new ClearingResponse();
                clearingResponse.setSuccess(false);
                clearingResponse.setMessage(message);
                return clearingResponse;
            }

        } catch (Exception e) {
            log.error("Error submitting ADL clearing: adlExecutionId=" + adlExecutionId, e);

            ClearingResponse clearingResponse = new ClearingResponse();
            clearingResponse.setSuccess(false);
            clearingResponse.setMessage("记账服务异常: " + e.getMessage());
            return clearingResponse;
        }
    }

    /**
     * 构造ADL记账请求
     */
    private ClearingRequest buildAdlClearingRequest(
            String adlExecutionId,
            Long targetUserId,
            Long targetPositionId,
            Long sourceUserId,
            String symbol,
            String side,
            BigDecimal adlPrice,
            BigDecimal adlQty,
            BigDecimal targetPnlChange) {

        ClearingRequest request = new ClearingRequest();
        request.setBizType("ADL");
        request.setBizSeq(adlExecutionId); // 使用ADL执行ID作为幂等性标识
        request.setSymbol(symbol);
        request.setTimestamp(System.currentTimeMillis());

        // 构造分录列表
        List<ClearingRequest.LedgerEntry> entries = new ArrayList<>();

        // 分录1：减少被ADL用户的持仓
        ClearingRequest.LedgerEntry entry1 = new ClearingRequest.LedgerEntry();
        entry1.setUserId(targetUserId);
        entry1.setAccountType("POSITION");
        entry1.setCurrency(symbol.replace("USDT", "")); // 例如BTCUSDT -> BTC
        entry1.setAmount(adlQty.negate()); // 负值表示减少
        entry1.setDirection("DEBIT");
        entry1.setDescription("ADL减仓: " + adlExecutionId);
        entries.add(entry1);

        // 分录2：增加被ADL用户的保证金（返还保证金）
        BigDecimal marginReturn = adlQty.multiply(adlPrice);
        ClearingRequest.LedgerEntry entry2 = new ClearingRequest.LedgerEntry();
        entry2.setUserId(targetUserId);
        entry2.setAccountType("MARGIN");
        entry2.setCurrency("USDT");
        entry2.setAmount(marginReturn);
        entry2.setDirection("CREDIT");
        entry2.setDescription("ADL返还保证金: " + adlExecutionId);
        entries.add(entry2);

        // 分录3：增加被ADL用户的已实现盈亏
        if (targetPnlChange != null && targetPnlChange.compareTo(BigDecimal.ZERO) != 0) {
            ClearingRequest.LedgerEntry entry3 = new ClearingRequest.LedgerEntry();
            entry3.setUserId(targetUserId);
            entry3.setAccountType("REALIZED_PNL");
            entry3.setCurrency("USDT");
            entry3.setAmount(targetPnlChange);
            entry3.setDirection(targetPnlChange.compareTo(BigDecimal.ZERO) > 0 ? "CREDIT" : "DEBIT");
            entry3.setDescription("ADL盈亏结算: " + adlExecutionId);
            entries.add(entry3);
        }

        // 分录4：减少穿仓用户的未平仓负债（由ADL分摊）
        ClearingRequest.LedgerEntry entry4 = new ClearingRequest.LedgerEntry();
        entry4.setUserId(sourceUserId);
        entry4.setAccountType("DEBT");
        entry4.setCurrency("USDT");
        entry4.setAmount(marginReturn.negate());
        entry4.setDirection("CREDIT");
        entry4.setDescription("ADL分摊穿仓损失: " + adlExecutionId);
        entries.add(entry4);

        request.setEntries(entries);

        // 附加信息
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("adlExecutionId", adlExecutionId);
        metadata.put("targetUserId", targetUserId);
        metadata.put("targetPositionId", targetPositionId);
        metadata.put("sourceUserId", sourceUserId);
        metadata.put("adlPrice", adlPrice.toPlainString());
        metadata.put("adlQty", adlQty.toPlainString());
        request.setMetadata(metadata);

        return request;
    }

    /**
     * 查询记账状态（用于幂等性检查）
     */
    public boolean checkClearingStatus(String bizSeq) {
        try {
            String url = clearingServiceUrl + "/internal/clearing/check?bizSeq={bizSeq}";

            Map<String, Object> params = new HashMap<>();
            params.put("bizSeq", bizSeq);

            Map<String, Object> response = restTemplate.getForObject(url, Map.class, params);

            if (response != null && (Integer) response.get("code") == 0) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                Boolean exists = (Boolean) data.get("exists");
                return exists != null && exists;
            }

            return false;

        } catch (Exception e) {
            log.error("Error checking clearing status: bizSeq=" + bizSeq, e);
            return false;
        }
    }

    // 辅助方法
    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }
}
