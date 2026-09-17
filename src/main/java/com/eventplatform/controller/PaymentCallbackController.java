package com.eventplatform.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.eventplatform.dto.Result;
import com.eventplatform.payment.GatewayResultStatus;
import com.eventplatform.payment.PaymentCallbackService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/payment-callbacks")
public class PaymentCallbackController {
    private final PaymentCallbackService callbacks;
    private final ObjectMapper json;

    public PaymentCallbackController(PaymentCallbackService callbacks, ObjectMapper json) {
        this.callbacks = callbacks;
        this.json = json;
    }

    @PostMapping("/simulated")
    public Result simulated(@RequestHeader("X-Simulated-Event-Id") String eventId,
            @RequestHeader("X-Simulated-Timestamp") long epochSeconds,
            @RequestHeader("X-Simulated-Signature") String signature,
            @RequestBody String rawBody) {
        CallbackPayload payload;
        try {
            payload = json.readValue(rawBody, CallbackPayload.class);
        } catch (JsonProcessingException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid callback JSON");
        }
        return Result.ok(callbacks.receive(new PaymentCallbackService.CallbackCommand("simulated", eventId,
                payload.kind(), payload.businessNumber(), payload.providerResultId(), payload.orderReference(),
                payload.amount(), payload.currency(), payload.status(), Instant.ofEpochSecond(epochSeconds),
                rawBody, signature)));
    }

    public record CallbackPayload(String kind, String businessNumber, String providerResultId,
            String orderReference, long amount, String currency, GatewayResultStatus status) {}
}
