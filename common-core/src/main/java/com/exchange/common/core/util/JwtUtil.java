package com.exchange.common.core.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类（共享版）
 * 
 * 被 api-gateway 和 user-core 共同使用
 */
@Slf4j
public class JwtUtil {

    private final String secret;
    private final Long expiration;
    private final SecretKey key;

    public JwtUtil(String secret, Long expiration) {
        this.secret = secret;
        this.expiration = expiration;
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成Token
     */
    public String generateToken(Long userId, String username, Long accountId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("username", username)
                .claim("accountId", accountId)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(key)
                .compact();
    }

    /**
     * 验证Token
     */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("[JwtUtil] Token expired");
        } catch (UnsupportedJwtException e) {
            log.warn("[JwtUtil] Token unsupported");
        } catch (MalformedJwtException e) {
            log.warn("[JwtUtil] Token malformed");
        } catch (SignatureException e) {
            log.warn("[JwtUtil] Signature validation failed");
        } catch (IllegalArgumentException e) {
            log.warn("[JwtUtil] Token empty or null");
        }
        return false;
    }

    /**
     * 从Token获取用户ID
     */
    public Long getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        return Long.valueOf(claims.getSubject());
    }

    /**
     * 从Token获取用户名
     */
    public String getUsernameFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("username", String.class);
    }

    /**
     * 从Token获取账户ID
     */
    public Long getAccountIdFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("accountId", Long.class);
    }

    /**
     * 获取Token剩余时间
     */
    public long getRemainingTime(String token) {
        try {
            Claims claims = parseToken(token);
            return claims.getExpiration().getTime() - System.currentTimeMillis();
        } catch (ExpiredJwtException e) {
            return 0;
        }
    }

    /**
     * 获取过期时间配置
     */
    public long getExpiration() {
        return expiration;
    }

    private Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
