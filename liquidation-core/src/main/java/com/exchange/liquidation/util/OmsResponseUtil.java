package com.exchange.liquidation.util;

import java.util.Map;

/**
 * OMS 内部接口返回解析工具。
 */
public final class OmsResponseUtil {

    private OmsResponseUtil() {
    }

    public static Long extractOrderId(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        if (raw instanceof Map<?, ?> map) {
            Long direct = extractOrderId(map.get("orderId"));
            if (direct != null) {
                return direct;
            }
            Object data = map.get("data");
            Long fromData = extractOrderId(data);
            if (fromData != null) {
                return fromData;
            }
            if (data instanceof Map<?, ?> dataMap) {
                return extractOrderId(dataMap.get("orderId"));
            }
        }
        return null;
    }

    public static String extractErrorMessage(Object raw) {
        if (raw == null) {
            return "empty response";
        }
        if (raw instanceof Map<?, ?> map) {
            Object code = map.get("errorCode");
            Object message = map.get("errorMessage");
            Object success = map.get("success");
            String codeText = code == null ? "" : String.valueOf(code).trim();
            String msgText = message == null ? "" : String.valueOf(message).trim();
            if (!msgText.isEmpty()) {
                return codeText.isEmpty() ? msgText : (codeText + ": " + msgText);
            }
            if (success != null && "false".equalsIgnoreCase(String.valueOf(success))) {
                return "OMS returned success=false";
            }
        }
        return "unexpected response: " + raw;
    }
}
