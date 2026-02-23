package com.exchange.common.core;

import lombok.Data;

/**
 * 统一API响应包装
 * 
 * @param <T> 数据类型
 */
@Data
public class ApiResponse<T> {
    
    /**
     * 状态码: 0成功, 非0失败
     */
    private int code;
    
    /**
     * 响应消息
     */
    private String msg;
    
    /**
     * 响应数据
     */
    private T data;
    
    /**
     * 时间戳
     */
    private long timestamp;

    public ApiResponse() {
        this.timestamp = System.currentTimeMillis();
    }

    public ApiResponse(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 成功响应
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    /**
     * 成功响应（无数据）
     */
    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    /**
     * 失败响应
     */
    public static <T> ApiResponse<T> error(int code, String msg) {
        return new ApiResponse<>(code, msg, null);
    }

    /**
     * 失败响应（默认code=500）
     */
    public static <T> ApiResponse<T> error(String msg) {
        return error(500, msg);
    }

    /**
     * 判断是否成功
     */
    public boolean isSuccess() {
        return code == 0;
    }
}
