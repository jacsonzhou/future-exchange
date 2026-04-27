package com.exchange.common.core;

import java.io.Serializable;

/**
 * 通用响应结果快捷类（兼容层）
 *
 * 与 {@link Result} 等价，提供更简洁的 API 调用方式。
 */
public class R<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private int code;
    private String message;
    private T data;
    private long timestamp;

    public R() {
        this.timestamp = System.currentTimeMillis();
    }

    public R(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> R<T> success(T data) {
        return new R<>(0, "success", data);
    }

    public static <T> R<T> success() {
        return new R<>(0, "success", null);
    }

    public static <T> R<T> error(String message) {
        return new R<>(-1, message, null);
    }

    public static <T> R<T> error(int code, String message) {
        return new R<>(code, message, null);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
