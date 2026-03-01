package com.exchange.marketmaker.interceptor;

import com.exchange.marketmaker.entity.MarketMaker;
import com.exchange.marketmaker.service.MarketMakerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 做市商身份验证拦截器
 */
@Slf4j
public class MmAuthInterceptor implements HandlerInterceptor {

    @Autowired
    private MarketMakerService marketMakerService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String userIdHeader = request.getHeader("X-User-Id");
        if (userIdHeader == null) {
            log.warn("[MM-Auth] Missing X-User-Id header");
            response.setStatus(401);
            response.getWriter().write("{\"code\":-1,\"message\":\"Missing user ID\"}");
            return false;
        }

        Long userId = Long.parseLong(userIdHeader);

        // 验证做市商身份
        MarketMaker mm = marketMakerService.getMarketMaker(userId);
        if (mm == null) {
            log.warn("[MM-Auth] User is not market maker, userId={}", userId);
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":-1,\"message\":\"Not a market maker\"}");
            return false;
        }

        // 检查做市商状态
        if (!"ACTIVE".equals(mm.getStatus())) {
            log.warn("[MM-Auth] Market maker status is not active, userId={}, status={}",
                    userId, mm.getStatus());
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":-1,\"message\":\"Market maker is not active\"}");
            return false;
        }

        // TODO: API频率限制检查
        // 根据做市商等级，限制API调用频率

        return true;
    }
}
