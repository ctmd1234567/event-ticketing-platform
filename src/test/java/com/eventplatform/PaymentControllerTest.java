package com.eventplatform;

import com.eventplatform.controller.PaymentController;
import com.eventplatform.dto.UserDTO;
import com.eventplatform.payment.EventPaymentService;
import com.eventplatform.payment.EventRefundService;
import com.eventplatform.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentControllerTest {
    private final EventPaymentService payments = mock(EventPaymentService.class);
    private final EventRefundService refunds = mock(EventRefundService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PaymentController(payments, refunds)).build();

    @BeforeEach
    void actor() {
        UserDTO user = new UserDTO();
        user.setId(42L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void clearActor() {
        UserHolder.removeUser();
    }

    @Test
    void ownedRoutesAlwaysPassAuthenticatedActorAndPaymentKey() throws Exception {
        mvc.perform(post("/api/v1/orders/11/payments").header("Idempotency-Key", "1234567890abcdef"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/payments/12")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/payments/12/refresh")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/refunds/13")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/refunds/13/refresh")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/payments/12/refunds")).andExpect(status().isOk());

        verify(payments).create(11L, 42L, "1234567890abcdef");
        verify(payments).payment(12L, 42L);
        verify(payments).refresh(12L, 42L);
        verify(refunds).refund(13L, 42L);
        verify(refunds).refresh(13L, 42L);
        verify(refunds).requestFullRefund(12L, 42L);
    }
}
