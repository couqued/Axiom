package com.axiom.strategy.dto;

/**
 * OrderClient buy/sell 결과.
 * success=true: 체결 성공
 * success=false: KIS API 오류, errorMsg에 원인 포함 (최대 100자)
 */
public record OrderResult(boolean success, String errorMsg) {

    public static OrderResult ok() {
        return new OrderResult(true, null);
    }

    public static OrderResult fail(String msg) {
        String trimmed = msg != null && msg.length() > 100 ? msg.substring(0, 100) + "..." : msg;
        return new OrderResult(false, trimmed);
    }
}
