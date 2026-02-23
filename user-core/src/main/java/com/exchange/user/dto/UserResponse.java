package com.exchange.user.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户信息响应
 */
@Data
public class UserResponse {

    private Long userId;
    private String username;
    private String email;
    private String phone;
    private Integer status;
    private String userType;
    private LocalDateTime lastLoginTime;
    private LocalDateTime createdAt;

    /**
     * 默认交易账户ID
     */
    private Long accountId;

    /**
     * JWT Token
     */
    private String token;
}
