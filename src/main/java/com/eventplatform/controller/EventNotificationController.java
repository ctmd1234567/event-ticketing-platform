package com.eventplatform.controller;

import com.eventplatform.dto.Result;
import com.eventplatform.notification.EventNotificationService;
import com.eventplatform.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class EventNotificationController {
    private final EventNotificationService notifications;

    public EventNotificationController(EventNotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public Result list() {
        return Result.ok(notifications.list(UserHolder.getUser().getId()));
    }
}
