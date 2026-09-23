package com.eventplatform.controller;

import com.eventplatform.dto.Result;
import com.eventplatform.notification.EventNotificationRedriveService;
import com.eventplatform.utils.UserHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/notifications")
@ConditionalOnProperty(name = "app.event-notifications.enabled", havingValue = "true")
public class EventNotificationAdminController {
    private final EventNotificationRedriveService redrive;

    public EventNotificationAdminController(EventNotificationRedriveService redrive) {
        this.redrive = redrive;
    }

    @PostMapping("/{eventId}/redrive")
    public Result redrive(@PathVariable String eventId, @Valid @RequestBody RedriveRequest request) {
        return Result.ok(redrive.redrive(eventId, UserHolder.getUser().getId(), request.reason()));
    }

    public record RedriveRequest(@NotBlank @Size(max = 255) String reason) {}
}
