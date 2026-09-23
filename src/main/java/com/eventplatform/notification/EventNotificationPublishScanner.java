package com.eventplatform.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.event-notifications.enabled", havingValue = "true")
@ConditionalOnProperty(name = "app.event-notifications.scan-enabled", havingValue = "true", matchIfMissing = true)
public class EventNotificationPublishScanner {
    private final EventNotificationPublisher publisher;

    public EventNotificationPublishScanner(EventNotificationPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${app.event-notifications.interval-ms:1000}")
    public void scan() {
        publisher.publish();
    }
}
