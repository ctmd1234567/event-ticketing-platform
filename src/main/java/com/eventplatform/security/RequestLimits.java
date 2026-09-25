package com.eventplatform.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class RequestLimits {
    private final AuthCodes codes;

    public RequestLimits(AuthCodes codes) {
        this.codes = codes;
    }

    public void auth(HttpServletRequest request) {
        // Do not trust arbitrary X-Forwarded-For headers; configure a trusted reverse proxy separately.
        codes.limit("auth:ip:{"+request.getRemoteAddr()+"}",30,60);
    }

}
