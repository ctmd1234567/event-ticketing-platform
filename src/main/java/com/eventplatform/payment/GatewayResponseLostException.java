package com.eventplatform.payment;

/**
 * Indicates that a simulated provider committed a result but the application did
 * not receive its response. It is intentionally distinct from a provider failure.
 */
public class GatewayResponseLostException extends RuntimeException {
    public GatewayResponseLostException(GatewayOperation operation, String businessNumber) {
        super("Simulated " + operation.name().toLowerCase(java.util.Locale.ROOT)
                + " response was lost after gateway commit for " + businessNumber);
    }
}
