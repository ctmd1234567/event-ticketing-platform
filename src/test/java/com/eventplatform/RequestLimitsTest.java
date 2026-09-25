package com.eventplatform;

import com.eventplatform.security.AuthCodes;
import com.eventplatform.security.RequestLimits;
import org.junit.jupiter.api.Test;
import jakarta.servlet.http.HttpServletRequest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestLimitsTest {
    @Test void authAdmissionUsesServerObservedAddress() {
        AuthCodes codes = mock(AuthCodes.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        RequestLimits limits = new RequestLimits(codes);

        limits.auth(request);

        verify(codes).limit("auth:ip:{127.0.0.1}", 30, 60);
    }
}
