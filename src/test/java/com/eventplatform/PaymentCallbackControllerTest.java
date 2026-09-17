package com.eventplatform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.eventplatform.controller.PaymentCallbackController;
import com.eventplatform.payment.PaymentCallbackService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentCallbackControllerTest {
    private final PaymentCallbackService callbacks = mock(PaymentCallbackService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new PaymentCallbackController(callbacks, new ObjectMapper()))
            .build();

    @Test
    void forwardsTheExactRawBodyAndSignatureHeaders() throws Exception {
        String raw = "{ \"kind\":\"PAYMENT\", \"businessNumber\":\"EP1\",\"providerResultId\":\"GP1\","
                + "\"orderReference\":\"EO1\",\"amount\":4750,\"currency\":\"CNY\",\"status\":\"SUCCEEDED\" }";
        mvc.perform(post("/api/v1/payment-callbacks/simulated")
                        .header("X-Simulated-Event-Id", "evt-1")
                        .header("X-Simulated-Timestamp", "123")
                        .header("X-Simulated-Signature", "abcd")
                        .contentType("application/json").content(raw))
                .andExpect(status().isOk());
        ArgumentCaptor<PaymentCallbackService.CallbackCommand> command =
                ArgumentCaptor.forClass(PaymentCallbackService.CallbackCommand.class);
        verify(callbacks).receive(command.capture());
        assertThat(command.getValue().rawBody()).isEqualTo(raw);
        assertThat(command.getValue().timestamp().getEpochSecond()).isEqualTo(123);
        assertThat(command.getValue().provider()).isEqualTo("simulated");
    }

    @Test
    void malformedJsonIsRejectedBeforeReceiptService() throws Exception {
        mvc.perform(post("/api/v1/payment-callbacks/simulated")
                        .header("X-Simulated-Event-Id", "evt-1")
                        .header("X-Simulated-Timestamp", "123")
                        .header("X-Simulated-Signature", "abcd")
                        .contentType("application/json").content("{"))
                .andExpect(status().isBadRequest());
    }
}
