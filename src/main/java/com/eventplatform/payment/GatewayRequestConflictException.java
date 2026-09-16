package com.eventplatform.payment;

/** Raised when a retried gateway business number has a different immutable request. */
public class GatewayRequestConflictException extends RuntimeException {
    public GatewayRequestConflictException(String message) {
        super(message);
    }
}
