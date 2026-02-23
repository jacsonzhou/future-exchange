package com.exchange.push.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订阅结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubscribeResult {
    
    private boolean success;
    private String message;
    private String channel;
    
    public static SubscribeResult success(String channel) {
        return new SubscribeResult(true, "Subscribed successfully", channel);
    }
    
    public static SubscribeResult error(String message) {
        return new SubscribeResult(false, message, null);
    }
    
    public static SubscribeResult error(String channel, String message) {
        return new SubscribeResult(false, message, channel);
    }
}
