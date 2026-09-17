package com.eventplatform;

import com.eventplatform.controller.PaymentRecoveryAdminController;
import com.eventplatform.dto.UserDTO;
import com.eventplatform.payment.EventPaymentService;
import com.eventplatform.payment.EventRefundService;
import com.eventplatform.payment.PaymentRecoveryAdminService;
import com.eventplatform.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentRecoveryAdminControllerTest {
    private final EventPaymentService payments = mock(EventPaymentService.class);
    private final EventRefundService refunds = mock(EventRefundService.class);
    private final PaymentRecoveryAdminService recovery = mock(PaymentRecoveryAdminService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new PaymentRecoveryAdminController(payments, refunds, recovery)).build();

    @BeforeEach
    void actor() {
        UserDTO user = new UserDTO();
        user.setId(1L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void clearActor() {
        UserHolder.removeUser();
    }

    @Test
    void recoveryQueryIsBoundedAndRetriesCarryActorKeyAndReason() throws Exception {
        mvc.perform(get("/api/v1/admin/payment-recovery").param("limit", "25").param("afterId", "100"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/payments/12/retry")
                        .header("Idempotency-Key", "1234567890abcdef")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"checked provider\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/refunds/13/retry")
                        .header("Idempotency-Key", "abcdef1234567890")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"provider recovered\"}"))
                .andExpect(status().isOk());

        verify(recovery).items(25, 100L);
        verify(payments).manualRetry(12L, 1L, "1234567890abcdef", "checked provider");
        verify(refunds).manualRetry(13L, 1L, "abcdef1234567890", "provider recovered");
    }
}
