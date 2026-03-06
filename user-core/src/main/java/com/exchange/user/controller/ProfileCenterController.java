package com.exchange.user.controller;

import com.exchange.user.client.OmsOrderClient;
import com.exchange.user.client.PositionSnapshotClient;
import com.exchange.user.client.SnapshotAccountClient;
import com.exchange.user.dto.Result;
import com.exchange.user.entity.TradingAccount;
import com.exchange.user.entity.User;
import com.exchange.user.mapper.TradingAccountMapper;
import com.exchange.user.mapper.UserMapper;
import com.exchange.user.security.JwtUtil;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 个人中心聚合接口
 *
 * 调用链：
 * API Gateway -> user-core(ProfileCenterController)
 *      -> snapshot-account-core / position-snapshot-core / oms-core
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/user/profile")
@RequiredArgsConstructor
public class ProfileCenterController {

    private static final int DEFAULT_TRADE_LIMIT = 50;
    private static final int MAX_TRADE_LIMIT = 200;
    private static final String DEFAULT_CURRENCY = "USDT";
    private static final String DEFAULT_COUNTRY = "CN";
    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
    private static final String DEFAULT_LANGUAGE = "zh-CN";

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;
    private final TradingAccountMapper tradingAccountMapper;
    private final SnapshotAccountClient snapshotAccountClient;
    private final PositionSnapshotClient positionSnapshotClient;
    private final OmsOrderClient omsOrderClient;

    @GetMapping("/overview")
    public Result<ProfileOverviewResponse> getOverview(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        AuthContext auth = resolveAuth(authorization);
        if (auth == null) {
            return Result.error(401, "无效的Token");
        }

        User user = userMapper.selectById(auth.getUserId());
        if (user == null) {
            return Result.error(404, "用户不存在");
        }
        TradingAccount account = tradingAccountMapper.selectDefaultByUserId(auth.getUserId());
        SnapshotAccountDTO snapshot = querySnapshot(auth.getUserId());
        List<PositionSnapshotDTO> positions = queryPositions(auth.getUserId());
        List<OmsOrderItemDTO> activeOrders = queryOmsOrders(
                auth.getUserId(),
                "NEW,PARTIALLY_FILLED,PENDING,OPEN,PENDING_RISK,FROZEN",
                null,
                0,
                100
        );
        List<OmsOrderItemDTO> filledOrders = queryOmsOrders(
                auth.getUserId(),
                "FILLED,PARTIALLY_FILLED",
                null,
                0,
                200
        );

        ProfileOverviewResponse response = new ProfileOverviewResponse();

        response.setUser(buildUserBrief(user));
        response.setAccount(buildAccountBrief(account));
        response.setBalance(buildBalanceSummary(snapshot));
        response.setStats(buildTradingStats(snapshot, positions, activeOrders, filledOrders));
        response.setSecurity(buildSecuritySummary(user, auth.getUserId()));
        return Result.success(response);
    }

    @GetMapping("/balances")
    public Result<List<ProfileAssetBalanceItem>> getBalances(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "assetKeyword", required = false) String assetKeyword,
            @RequestParam(value = "accountType", required = false, defaultValue = "ALL") String accountType
    ) {
        AuthContext auth = resolveAuth(authorization);
        if (auth == null) {
            return Result.error(401, "无效的Token");
        }

        SnapshotAccountDTO snapshot = querySnapshot(auth.getUserId());
        ProfileAssetBalanceItem item = new ProfileAssetBalanceItem();
        item.setAsset(firstNonBlank(snapshot.getCurrency(), DEFAULT_CURRENCY));
        item.setAccountType("CONTRACT");
        item.setTotal(nz(snapshot.getEquity()));
        item.setAvailable(nz(snapshot.getAvailable()));
        item.setFrozen(nz(snapshot.getFrozen()));
        item.setPositionMargin(nz(snapshot.getPositionMargin()));
        item.setUnrealizedPnl(nz(snapshot.getUnrealizedPnl()));
        item.setRealizedPnl(nz(snapshot.getRealizedPnl()));
        item.setUsdtValuation(nz(snapshot.getEquity()));
        item.setChange24hRate(BigDecimal.ZERO);
        item.setUpdatedAt(snapshot.getUpdatedAt());

        List<ProfileAssetBalanceItem> result = new ArrayList<>();
        result.add(item);

        String normalizedAccountType = normalizeAccountType(accountType);
        if (!"ALL".equals(normalizedAccountType) && !"CONTRACT".equals(normalizedAccountType)) {
            return Result.success(List.of());
        }

        String keyword = assetKeyword == null ? "" : assetKeyword.trim().toUpperCase(Locale.ROOT);
        if (StringUtils.hasText(keyword)) {
            result = result.stream()
                    .filter(v -> v.getAsset() != null && v.getAsset().toUpperCase(Locale.ROOT).contains(keyword))
                    .toList();
        }

        return Result.success(result);
    }

    @GetMapping("/trades")
    public Result<List<ProfileTradeHistoryItem>> getTradeHistory(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "side", required = false) String side,
            @RequestParam(value = "limit", required = false, defaultValue = "50") Integer limit
    ) {
        AuthContext auth = resolveAuth(authorization);
        if (auth == null) {
            return Result.error(401, "无效的Token");
        }

        int safeLimit = Math.max(1, Math.min(limit == null ? DEFAULT_TRADE_LIMIT : limit, MAX_TRADE_LIMIT));
        String normalizedSide = normalizeSide(side);
        String normalizedSymbol = normalizeSymbol(symbol);

        List<OmsOrderItemDTO> orders = queryOmsOrders(
                auth.getUserId(),
                "FILLED,PARTIALLY_FILLED",
                normalizedSymbol,
                0,
                safeLimit
        );

        List<ProfileTradeHistoryItem> items = orders.stream()
                .filter(order -> "ALL".equals(normalizedSide) || normalizedSide.equals(order.getSide()))
                .sorted(Comparator.comparing(ProfileCenterController::safeCreateTime).reversed())
                .map(this::toTradeHistoryItem)
                .limit(safeLimit)
                .toList();

        return Result.success(items);
    }

    @GetMapping("/info")
    public Result<ProfileInfoResponse> getProfileInfo(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        AuthContext auth = resolveAuth(authorization);
        if (auth == null) {
            return Result.error(401, "无效的Token");
        }
        User user = userMapper.selectById(auth.getUserId());
        if (user == null) {
            return Result.error(404, "用户不存在");
        }
        return Result.success(toProfileInfo(user));
    }

    @PutMapping("/info")
    public Result<ProfileInfoResponse> updateProfileInfo(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody UpdateProfileInfoRequest request
    ) {
        AuthContext auth = resolveAuth(authorization);
        if (auth == null) {
            return Result.error(401, "无效的Token");
        }
        User user = userMapper.selectById(auth.getUserId());
        if (user == null) {
            return Result.error(404, "用户不存在");
        }
        if (request == null) {
            return Result.error(400, "请求体不能为空");
        }

        String nextEmail = normalizeNullable(request.getEmail());
        String nextPhone = normalizeNullable(request.getPhone());

        if (StringUtils.hasText(nextEmail) && userMapper.countByEmail(nextEmail, user.getId()) > 0) {
            return Result.error(400, "邮箱已被占用");
        }
        if (StringUtils.hasText(nextPhone) && userMapper.countByPhone(nextPhone, user.getId()) > 0) {
            return Result.error(400, "手机号已被占用");
        }

        boolean changed = false;
        if (!Objects.equals(nextEmail, normalizeNullable(user.getEmail()))) {
            user.setEmail(nextEmail);
            changed = true;
        }
        if (!Objects.equals(nextPhone, normalizeNullable(user.getPhone()))) {
            user.setPhone(nextPhone);
            changed = true;
        }
        if (changed) {
            userMapper.updateById(user);
            log.info("[ProfileCenter] Updated profile info, userId={}, emailChanged={}, phoneChanged={}",
                    user.getId(), nextEmail != null, nextPhone != null);
        }

        return Result.success(toProfileInfo(user));
    }

    private ProfileUserBrief buildUserBrief(User user) {
        ProfileUserBrief brief = new ProfileUserBrief();
        brief.setUserId(user.getId());
        brief.setUsername(user.getUsername());
        brief.setEmail(user.getEmail());
        brief.setPhone(user.getPhone());
        brief.setUserType(user.getUserType());
        brief.setStatus(user.getStatus());
        brief.setKycLevel(user.getKycLevel() == null ? 0 : user.getKycLevel());
        brief.setCreatedAt(user.getCreatedAt() == null ? null : user.getCreatedAt().toString());
        brief.setLastLoginTime(user.getLastLoginTime() == null ? null : user.getLastLoginTime().toString());
        return brief;
    }

    private ProfileAccountBrief buildAccountBrief(TradingAccount account) {
        ProfileAccountBrief brief = new ProfileAccountBrief();
        if (account == null) {
            brief.setAccountId(null);
            brief.setAccountType("STANDARD");
            brief.setMarginMode("CROSS");
            brief.setDefaultLeverage(10);
            brief.setFunded(false);
            return brief;
        }
        brief.setAccountId(account.getId());
        brief.setAccountType(account.getAccountType());
        brief.setMarginMode(account.getMarginMode());
        brief.setDefaultLeverage(account.getDefaultLeverage());
        brief.setFunded(Boolean.TRUE.equals(account.getFunded()));
        return brief;
    }

    private ProfileBalanceSummary buildBalanceSummary(SnapshotAccountDTO snapshot) {
        ProfileBalanceSummary summary = new ProfileBalanceSummary();
        summary.setCurrency(firstNonBlank(snapshot.getCurrency(), DEFAULT_CURRENCY));
        summary.setEquity(nz(snapshot.getEquity()));
        summary.setAvailable(nz(snapshot.getAvailable()));
        summary.setFrozen(nz(snapshot.getFrozen()));
        summary.setPositionMargin(nz(snapshot.getPositionMargin()));
        summary.setUnrealizedPnl(nz(snapshot.getUnrealizedPnl()));
        summary.setRealizedPnl(nz(snapshot.getRealizedPnl()));
        return summary;
    }

    private ProfileTradingStats buildTradingStats(
            SnapshotAccountDTO snapshot,
            List<PositionSnapshotDTO> positions,
            List<OmsOrderItemDTO> activeOrders,
            List<OmsOrderItemDTO> filledOrders
    ) {
        ProfileTradingStats stats = new ProfileTradingStats();
        BigDecimal equity = nz(snapshot.getEquity());
        BigDecimal positionMargin = nz(snapshot.getPositionMargin());

        int positionCount = 0;
        int longCount = 0;
        int shortCount = 0;
        for (PositionSnapshotDTO position : positions) {
            if (position == null || position.getSize() == null
                    || position.getSize().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            positionCount++;
            if (position.getPositionSide() != null && position.getPositionSide() == 1) {
                longCount++;
            } else if (position.getPositionSide() != null && position.getPositionSide() == 2) {
                shortCount++;
            }
        }

        stats.setPositionCount(positionCount);
        stats.setLongPositionCount(longCount);
        stats.setShortPositionCount(shortCount);
        stats.setActiveOrderCount(activeOrders == null ? 0 : activeOrders.size());
        stats.setTodayFilledCount(countTodayOrders(filledOrders));
        stats.setWithdrawable(nz(snapshot.getAvailable()));

        BigDecimal riskRate = BigDecimal.ZERO;
        if (equity.compareTo(BigDecimal.ZERO) > 0) {
            riskRate = positionMargin.multiply(BigDecimal.valueOf(100))
                    .divide(equity, 2, RoundingMode.HALF_UP);
        }
        stats.setRiskRate(riskRate);
        return stats;
    }

    private ProfileSecuritySummary buildSecuritySummary(User user, Long userId) {
        ProfileSecuritySummary summary = new ProfileSecuritySummary();
        summary.setGoogle2faEnabled(StringUtils.hasText(user.getGoogleSecret()));
        summary.setWithdrawWhitelistEnabled(false);
        summary.setApiKeyCount(userMapper.countActiveApiKeys(userId));
        return summary;
    }

    private int countTodayOrders(List<OmsOrderItemDTO> orders) {
        if (orders == null || orders.isEmpty()) {
            return 0;
        }
        long start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        int count = 0;
        for (OmsOrderItemDTO order : orders) {
            Long time = safeCreateTime(order);
            if (time != null && time >= start) {
                count++;
            }
        }
        return count;
    }

    private ProfileTradeHistoryItem toTradeHistoryItem(OmsOrderItemDTO order) {
        ProfileTradeHistoryItem item = new ProfileTradeHistoryItem();
        item.setOrderId(order.getOrderId());
        item.setSymbol(order.getSymbol());
        item.setSide(order.getSide());
        item.setType(order.getType());
        item.setStatus(order.getStatus());
        item.setCreateTime(order.getCreateTime());
        item.setExecutionMode(order.getExecutionMode());

        BigDecimal price = parseDecimal(order.getPrice());
        BigDecimal quantity = parseDecimal(order.getQuantity());
        BigDecimal filledQuantity = parseDecimal(order.getFilledQuantity());

        item.setPrice(price);
        item.setQuantity(quantity);
        item.setFilledQuantity(filledQuantity);
        item.setFilledAmount(price.multiply(filledQuantity));
        item.setFee(null);
        item.setRealizedPnl(null);
        return item;
    }

    private SnapshotAccountDTO querySnapshot(Long userId) {
        try {
            SnapshotAccountDTO response = snapshotAccountClient.getAccountSnapshot(userId);
            return response == null ? SnapshotAccountDTO.empty(userId) : response;
        } catch (Exception e) {
            log.warn("[ProfileCenter] Query snapshot failed, userId={}, reason={}", userId, e.getMessage());
            return SnapshotAccountDTO.empty(userId);
        }
    }

    private List<PositionSnapshotDTO> queryPositions(Long userId) {
        try {
            List<PositionSnapshotDTO> response = positionSnapshotClient.getAllPositions(userId);
            return response == null ? List.of() : response;
        } catch (Exception e) {
            log.warn("[ProfileCenter] Query positions failed, userId={}, reason={}", userId, e.getMessage());
            return List.of();
        }
    }

    private List<OmsOrderItemDTO> queryOmsOrders(
            Long userId,
            String status,
            String symbol,
            Integer offset,
            Integer limit
    ) {
        try {
            OmsOrderListResponse response = omsOrderClient.queryOrderList(userId, status, symbol, offset, limit);
            if (response == null || response.getOrders() == null) {
                return List.of();
            }
            return response.getOrders();
        } catch (Exception e) {
            log.warn("[ProfileCenter] Query OMS orders failed, userId={}, status={}, symbol={}, reason={}",
                    userId, status, symbol, e.getMessage());
            return List.of();
        }
    }

    private AuthContext resolveAuth(String authorization) {
        if (!StringUtils.hasText(authorization)) {
            return null;
        }
        String token = authorization.trim();
        if (token.startsWith("Bearer ")) {
            token = token.substring(7).trim();
        }
        if (!jwtUtil.validateToken(token)) {
            return null;
        }
        AuthContext auth = new AuthContext();
        auth.setUserId(jwtUtil.getUserIdFromToken(token));
        auth.setAccountId(jwtUtil.getAccountIdFromToken(token));
        auth.setUsername(jwtUtil.getUsernameFromToken(token));
        return auth;
    }

    private ProfileInfoResponse toProfileInfo(User user) {
        ProfileInfoResponse response = new ProfileInfoResponse();
        response.setUsername(user.getUsername());
        response.setEmail(user.getEmail());
        response.setPhone(user.getPhone());
        response.setCountry(DEFAULT_COUNTRY);
        response.setTimezone(DEFAULT_TIMEZONE);
        response.setLanguage(DEFAULT_LANGUAGE);
        response.setCreatedAt(user.getCreatedAt() == null ? null : user.getCreatedAt().toString());
        response.setLastLoginTime(user.getLastLoginTime() == null ? null : user.getLastLoginTime().toString());
        return response;
    }

    private String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeAccountType(String accountType) {
        if (!StringUtils.hasText(accountType)) {
            return "ALL";
        }
        return accountType.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSide(String side) {
        if (!StringUtils.hasText(side)) {
            return "ALL";
        }
        String normalized = side.trim().toUpperCase(Locale.ROOT);
        if ("BUY".equals(normalized) || "SELL".equals(normalized)) {
            return normalized;
        }
        return "ALL";
    }

    private String normalizeSymbol(String symbol) {
        if (!StringUtils.hasText(symbol)) {
            return null;
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static Long safeCreateTime(OmsOrderItemDTO order) {
        return order == null ? null : order.getCreateTime();
    }

    private String firstNonBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal parseDecimal(String value) {
        if (!StringUtils.hasText(value)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (Exception ignore) {
            return BigDecimal.ZERO;
        }
    }

    @Data
    private static class AuthContext {
        private Long userId;
        private Long accountId;
        private String username;
    }

    @Data
    public static class ProfileOverviewResponse {
        private ProfileUserBrief user;
        private ProfileAccountBrief account;
        private ProfileBalanceSummary balance;
        private ProfileTradingStats stats;
        private ProfileSecuritySummary security;
    }

    @Data
    public static class ProfileUserBrief {
        private Long userId;
        private String username;
        private String email;
        private String phone;
        private Integer status;
        private String userType;
        private Integer kycLevel;
        private String createdAt;
        private String lastLoginTime;
    }

    @Data
    public static class ProfileAccountBrief {
        private Long accountId;
        private String accountType;
        private String marginMode;
        private Integer defaultLeverage;
        private boolean funded;
    }

    @Data
    public static class ProfileBalanceSummary {
        private String currency;
        private BigDecimal equity;
        private BigDecimal available;
        private BigDecimal frozen;
        private BigDecimal positionMargin;
        private BigDecimal unrealizedPnl;
        private BigDecimal realizedPnl;
    }

    @Data
    public static class ProfileTradingStats {
        private int positionCount;
        private int longPositionCount;
        private int shortPositionCount;
        private int activeOrderCount;
        private int todayFilledCount;
        private BigDecimal withdrawable;
        private BigDecimal riskRate;
    }

    @Data
    public static class ProfileSecuritySummary {
        private boolean google2faEnabled;
        private boolean withdrawWhitelistEnabled;
        private int apiKeyCount;
    }

    @Data
    public static class ProfileAssetBalanceItem {
        private String asset;
        private String accountType;
        private BigDecimal total;
        private BigDecimal available;
        private BigDecimal frozen;
        private BigDecimal positionMargin;
        private BigDecimal unrealizedPnl;
        private BigDecimal realizedPnl;
        private BigDecimal usdtValuation;
        private BigDecimal change24hRate;
        private Long updatedAt;
    }

    @Data
    public static class ProfileTradeHistoryItem {
        private String orderId;
        private String symbol;
        private String side;
        private String type;
        private String status;
        private Long createTime;
        private String executionMode;
        private BigDecimal price;
        private BigDecimal quantity;
        private BigDecimal filledQuantity;
        private BigDecimal filledAmount;
        private BigDecimal fee;
        private BigDecimal realizedPnl;
    }

    @Data
    public static class ProfileInfoResponse {
        private String username;
        private String email;
        private String phone;
        private String country;
        private String timezone;
        private String language;
        private String createdAt;
        private String lastLoginTime;
    }

    @Data
    public static class UpdateProfileInfoRequest {
        private String email;
        private String phone;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SnapshotAccountDTO {
        private Long userId;
        private String currency;
        private BigDecimal available;
        private BigDecimal frozen;
        private BigDecimal positionMargin;
        private BigDecimal unrealizedPnl;
        private BigDecimal realizedPnl;
        private BigDecimal equity;
        private Long updatedAt;

        public static SnapshotAccountDTO empty(Long userId) {
            SnapshotAccountDTO dto = new SnapshotAccountDTO();
            dto.setUserId(userId);
            dto.setCurrency(DEFAULT_CURRENCY);
            dto.setAvailable(BigDecimal.ZERO);
            dto.setFrozen(BigDecimal.ZERO);
            dto.setPositionMargin(BigDecimal.ZERO);
            dto.setUnrealizedPnl(BigDecimal.ZERO);
            dto.setRealizedPnl(BigDecimal.ZERO);
            dto.setEquity(BigDecimal.ZERO);
            dto.setUpdatedAt(System.currentTimeMillis());
            return dto;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PositionSnapshotDTO {
        private String symbol;
        private Integer positionSide;
        private BigDecimal size;
        private BigDecimal unrealizedPnl;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OmsOrderListResponse {
        private List<OmsOrderItemDTO> orders;
        private Long total;
        private Integer offset;
        private Integer limit;
        private Boolean hasMore;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OmsOrderItemDTO {
        private String orderId;
        private String symbol;
        private String side;
        private String type;
        private String price;
        private String quantity;
        private String filledQuantity;
        private String status;
        private String executionMode;
        private Long createTime;
    }
}
