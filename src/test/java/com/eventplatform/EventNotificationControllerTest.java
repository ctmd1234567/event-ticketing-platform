package com.eventplatform;

import com.eventplatform.controller.EventNotificationController;
import com.eventplatform.dto.UserDTO;
import com.eventplatform.notification.EventNotificationService;
import com.eventplatform.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EventNotificationControllerTest {
    private final EventNotificationService notifications = mock(EventNotificationService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new EventNotificationController(notifications)).build();

    @AfterEach
    void clearActor() {
        UserHolder.removeUser();
    }

    @Test
    void listRouteUsesOnlyAuthenticatedUserId() throws Exception {
        UserDTO actor = new UserDTO();
        actor.setId(42L);
        UserHolder.saveUser(actor);
        when(notifications.list(42L)).thenReturn(List.of(new EventNotificationService.NotificationView(
                1L, "ORDER_PAID:10", 10L, "ORDER_PAID", "Order payment succeeded", Instant.now())));

        mvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].eventId").value("ORDER_PAID:10"));
        verify(notifications).list(42L);
    }
}
