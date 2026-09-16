package com.eventplatform.payment;

public enum GatewayResultStatus {
    PROCESSING,
    SUCCEEDED,
    FAILED;

    static GatewayResultStatus configured(String value, String property) {
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(property + " must be PROCESSING, SUCCEEDED, or FAILED", invalid);
        }
    }
}
