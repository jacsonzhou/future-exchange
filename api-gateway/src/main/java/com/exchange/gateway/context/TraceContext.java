package com.exchange.gateway.context;

/**
 * 链路追踪上下文
 * 使用 ThreadLocal 存储追踪信息
 */
public class TraceContext {
    
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<Long> START_TIME = new ThreadLocal<>();
    
    /**
     * 设置TraceId
     */
    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);
    }
    
    /**
     * 获取TraceId
     */
    public static String getTraceId() {
        return TRACE_ID.get();
    }
    
    /**
     * 设置RequestId
     */
    public static void setRequestId(String requestId) {
        REQUEST_ID.set(requestId);
    }
    
    /**
     * 获取RequestId
     */
    public static String getRequestId() {
        return REQUEST_ID.get();
    }
    
    /**
     * 设置UserId
     */
    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }
    
    /**
     * 获取UserId
     */
    public static Long getUserId() {
        return USER_ID.get();
    }
    
    /**
     * 设置开始时间
     */
    public static void setStartTime(long startTime) {
        START_TIME.set(startTime);
    }
    
    /**
     * 获取开始时间
     */
    public static Long getStartTime() {
        return START_TIME.get();
    }
    
    /**
     * 清空上下文
     */
    public static void clear() {
        TRACE_ID.remove();
        REQUEST_ID.remove();
        USER_ID.remove();
        START_TIME.remove();
    }
}

