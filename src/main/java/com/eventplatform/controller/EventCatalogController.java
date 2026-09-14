package com.eventplatform.controller;

import com.eventplatform.catalog.EventCatalogService;
import com.eventplatform.dto.Result;
import com.eventplatform.utils.UserHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1")
public class EventCatalogController {
    private final EventCatalogService catalog;

    public EventCatalogController(EventCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/events")
    public Result events() {
        return Result.ok(catalog.listEvents());
    }

    @GetMapping("/events/{eventId}")
    public Result event(@PathVariable long eventId) {
        return Result.ok(catalog.event(eventId));
    }

    @GetMapping("/sessions/{sessionId}/ticket-tiers")
    public Result ticketTiers(@PathVariable long sessionId) {
        return Result.ok(catalog.ticketTiers(sessionId));
    }

    @PostMapping("/admin/events")
    public Result createEvent(@Valid @RequestBody CreateEventRequest request) {
        return Result.ok(catalog.createEvent(actorId(), request.title(), request.description(), request.venue()));
    }

    @PostMapping("/admin/events/{eventId}/sessions")
    public Result addSession(@PathVariable long eventId, @Valid @RequestBody CreateSessionRequest request) {
        return Result.ok(catalog.addSession(eventId, request.name(), request.startsAt(), request.endsAt(),
                request.salesStartAt(), request.salesEndAt()));
    }

    @PostMapping("/admin/sessions/{sessionId}/ticket-tiers")
    public Result addTicketTier(@PathVariable long sessionId, @Valid @RequestBody CreateTierRequest request) {
        return Result.ok(catalog.addTicketTier(sessionId, request.name(), request.unitPrice(),
                request.currency(), request.capacity()));
    }

    @PostMapping("/admin/events/{eventId}/publish")
    public Result publish(@PathVariable long eventId) {
        catalog.publish(eventId);
        return Result.ok();
    }

    @PostMapping("/admin/events/{eventId}/take-off-sale")
    public Result takeOffSale(@PathVariable long eventId) {
        catalog.takeOffSale(eventId);
        return Result.ok();
    }

    private long actorId() {
        return UserHolder.getUser().getId();
    }

    public record CreateEventRequest(
            @NotBlank @Size(max = 160) String title,
            @Size(max = 2000) String description,
            @NotBlank @Size(max = 255) String venue) {}

    public record CreateSessionRequest(
            @NotBlank @Size(max = 160) String name,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @NotNull Instant salesStartAt,
            @NotNull Instant salesEndAt) {}

    public record CreateTierRequest(
            @NotBlank @Size(max = 120) String name,
            @PositiveOrZero long unitPrice,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @Positive int capacity) {}
}
