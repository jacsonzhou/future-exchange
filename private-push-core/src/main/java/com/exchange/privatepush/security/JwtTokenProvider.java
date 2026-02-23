package com.exchange.privatepush.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * JWT Token 提供者
 *
 * 🔥 核心职责：
 * 1. JWT Token 生成和验证
 * 2. Token 刷新机制
 * 3. Token 撤销（Redis黑名单）
 * 4. 防重放攻击（nonce机制）
 *
 * 安全特性：
 * - Token 30分钟过期，支持刷新
 * - 支持强制下线（黑名单）
 * - 防重放攻击（nonce + 5分钟窗口）
 *
 * @author Exchange Team
 * @version 1.0.0
 */
@Slf4j
@Component
public class JwtTokenProvider {

    @Value("${jwt.secret:future-exchange-user-core-secret-key-2024}")
    private String jwtSecret;

    @Value("${private.push.jwt.expiration-minutes:30}")
    private int jwtExpirationMinutes;

    @Value("${private.push.jwt.refresh-minutes:60}")
    private int jwtRefreshMinutes;

    private SecretKey secretKey;

    private final RedisTemplate<String, Object> redisTemplate;

    // Redis Key前缀
    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";
    private static final String NONCE_PREFIX = "jwt:nonce:";

    public JwtTokenProvider(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        // 生成密钥（必须至少256位）
        this.secretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        log.info("[JwtTokenProvider] Initialized with expiration={}min", jwtExpirationMinutes);
    }

    /**
     * 生成 JWT Token
     *
     * @param userId 用户ID
     * @return JWT Token
     */
    public String generateToken(Long userId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationMinutes * 60 * 1000L);

        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("userId", userId)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 生成刷新 Token
     *
     * @param userId 用户ID
     * @return Refresh Token
     */
    public String generateRefreshToken(Long userId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtRefreshMinutes * 60 * 1000L);

        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("userId", userId)
                .claim("type", "refresh")
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 验证 Token 并提取 userId
     *
     * @param token JWT Token
     * @return userId，验证失败返回null
     */
    public Long validateToken(String token) {
        try {
            // 解析 Token
            Claims claims = Jwts.parser()
                    .setSigningKey(secretKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            // 从 sub (subject) 获取 userId，兼容 User Core 生成的 Token
            Long userId = Long.valueOf(claims.getSubject());
            
            // 检查是否在黑名单中
            if (isTokenBlacklisted(token)) {
                log.warn("[JwtTokenProvider] Token is blacklisted, userId={}", userId);
                return null;
            }

            // 防重放攻击：检查nonce
            String nonce = claims.get("nonce", String.class);
            if (nonce != null && !checkAndRecordNonce(nonce)) {
                log.warn("[JwtTokenProvider] Nonce replay detected, userId={}", userId);
                return null;
            }

            return userId;

        } catch (ExpiredJwtException e) {
            log.debug("[JwtTokenProvider] Token expired");
            return null;
        } catch (UnsupportedJwtException e) {
            log.warn("[JwtTokenProvider] Unsupported JWT token");
            return null;
        } catch (MalformedJwtException e) {
            log.warn("[JwtTokenProvider] Malformed JWT token");
            return null;
        } catch (SignatureException e) {
            log.warn("[JwtTokenProvider] Invalid JWT signature");
            return null;
        } catch (IllegalArgumentException e) {
            log.warn("[JwtTokenProvider] JWT claims string is empty");
            return null;
        }
    }

    /**
     * 刷新 Token
     *
     * @param refreshToken 刷新Token
     * @return 新的Access Token，失败返回null
     */
    public String refreshToken(String refreshToken) {
        try {
            Claims claims = Jwts.parser()
                    .setSigningKey(secretKey)
                    .build()
                    .parseClaimsJws(refreshToken)
                    .getBody();

            // 检查是否是刷新Token
            String type = claims.get("type", String.class);
            if (!"refresh".equals(type)) {
                log.warn("[JwtTokenProvider] Not a refresh token");
                return null;
            }

            // 检查是否在黑名单中
            if (isTokenBlacklisted(refreshToken)) {
                log.warn("[JwtTokenProvider] Refresh token is blacklisted");
                return null;
            }

            Long userId = claims.get("userId", Long.class);

            // 生成新的Access Token
            return generateToken(userId);

        } catch (JwtException e) {
            log.warn("[JwtTokenProvider] Invalid refresh token", e);
            return null;
        }
    }

    /**
     * 将 Token 加入黑名单（强制下线）
     *
     * @param token JWT Token
     * @param userId 用户ID
     */
    public void revokeToken(String token, Long userId) {
        try {
            // 解析 Token 获取过期时间
            Claims claims = Jwts.parser()
                    .setSigningKey(secretKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            Date expiration = claims.getExpiration();
            long ttl = expiration.getTime() - System.currentTimeMillis();

            if (ttl > 0) {
                // 加入黑名单，过期时间与Token一致
                String key = BLACKLIST_PREFIX + token;
                redisTemplate.opsForValue().set(key, userId, ttl, TimeUnit.MILLISECONDS);

                log.info("[JwtTokenProvider] Token revoked, userId={}", userId);
            }

        } catch (JwtException e) {
            log.warn("[JwtTokenProvider] Failed to revoke token", e);
        }
    }

    /**
     * 检查 Token 是否在黑名单中
     *
     * @param token JWT Token
     * @return true=在黑名单中，false=不在
     */
    private boolean isTokenBlacklisted(String token) {
        String key = BLACKLIST_PREFIX + token;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * 检查并记录 nonce（防重放攻击）
     *
     * @param nonce 随机数
     * @return true=首次使用，false=重放攻击
     */
    private boolean checkAndRecordNonce(String nonce) {
        String key = NONCE_PREFIX + nonce;

        // 使用 setIfAbsent 原子操作
        Boolean success = redisTemplate.opsForValue().setIfAbsent(
                key,
                System.currentTimeMillis(),
                5,
                TimeUnit.MINUTES
        );

        return Boolean.TRUE.equals(success);
    }

    /**
     * 从 Token 中提取 userId（不验证）
     *
     * @param token JWT Token
     * @return userId
     */
    public Long extractUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .setSigningKey(secretKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            return claims.get("userId", Long.class);

        } catch (JwtException e) {
            return null;
        }
    }

    /**
     * 检查 Token 是否即将过期（5分钟内）
     *
     * @param token JWT Token
     * @return true=即将过期，false=未过期
     */
    public boolean isTokenExpiringSoon(String token) {
        try {
            Claims claims = Jwts.parser()
                    .setSigningKey(secretKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            Date expiration = claims.getExpiration();
            long ttl = expiration.getTime() - System.currentTimeMillis();

            return ttl < 5 * 60 * 1000; // 5分钟

        } catch (JwtException e) {
            return true;
        }
    }
}
