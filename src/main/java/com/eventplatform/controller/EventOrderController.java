package com.eventplatform.controller;

import com.eventplatform.dto.Result;
import com.eventplatform.order.EventOrderService;
import com.eventplatform.utils.UserHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class EventOrderController {
    private final EventOrderService orders;

    public EventOrderController(EventOrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    public Result create(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        return Result.ok(orders.create(actorId(), idempotencyKey, request.ticketTierId(), request.quantity()));
    }

    @GetMapping("/{orderId}")
    public Result order(@PathVariable long orderId) {
        return Result.ok(orders.order(orderId, actorId()));
    }

    @GetMapping
    public Result orders() {
        return Result.ok(orders.orders(actorId()));
    }

    @PostMapping("/{orderId}/cancel")
    public Result cancel(@PathVariable long orderId) {
        return Result.ok(orders.cancel(orderId, actorId()));
    }

    private long actorId() {
        return UserHolder.getUser().getId();
    }

    public record CreateOrderRequest(@Min(1) long ticketTierId, @Min(1) int quantity) {}
}
