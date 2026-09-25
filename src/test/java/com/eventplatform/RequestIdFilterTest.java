package com.eventplatform;

import com.eventplatform.config.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {
    @Test
    void responseAndLogShareRequestIdWithoutLeakingToNextRequest() throws Exception {
        var filter = new RequestIdFilter();
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var loggedId = new AtomicReference<String>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                loggedId.set(MDC.get("requestId")));

        assertThat(loggedId.get()).isEqualTo(response.getHeader("X-Request-Id"));
        assertThat(UUID.fromString(loggedId.get())).isNotNull();
        assertThat(MDC.get("requestId")).isNull();
    }
}
