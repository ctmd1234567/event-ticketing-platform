package com.eventplatform;

import com.eventplatform.controller.EventOrderController;
import com.eventplatform.dto.UserDTO;
import com.eventplatform.order.EventOrderService;
import com.eventplatform.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EventOrderControllerTest {
    private final EventOrderService orders = mock(EventOrderService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new EventOrderController(orders))
            .build();

    @AfterEach
    void clearActor() {
        UserHolder.removeUser();
    }

    @Test
    void cancelRouteUsesAuthenticatedActor() throws Exception {
        UserDTO actor = new UserDTO();
        actor.setId(42L);
        UserHolder.saveUser(actor);
        Instant now = Instant.now();
        when(orders.cancel(1L, 42L)).thenReturn(new EventOrderService.OrderView(
                1L,
                "EO1",
                42L,
                10L,
                20L,
                30L,
                1,
                4750L,
                4750L,
                "CNY",
                "CLOSED",
                "hidden-key",
                "hidden-hash",
                now.plusSeconds(30),
                "USER_CANCELED",
                now));

        mvc.perform(post("/api/v1/orders/1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.closeReason").value("USER_CANCELED"));
        verify(orders).cancel(1L, 42L);
    }
}
