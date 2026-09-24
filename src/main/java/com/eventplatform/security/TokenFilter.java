package com.eventplatform.security;

import cn.hutool.core.bean.BeanUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.eventplatform.dto.Result;
import com.eventplatform.dto.UserDTO;
import com.eventplatform.utils.RedisConstants;
import com.eventplatform.utils.UserHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.MDC;
import java.io.IOException;
import java.util.*;

public class TokenFilter extends OncePerRequestFilter {
    private static final DefaultRedisScript<List> READ_SESSION = sessionScript();
    private final StringRedisTemplate redis;
    private final Set<String> admins;
    private final ObjectMapper json;
    private final int sessionTtlSeconds;
    private final int refreshThresholdSeconds;

    private static DefaultRedisScript<List> sessionScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("auth-session.lua"));
        script.setResultType(List.class);
        return script;
    }

    public TokenFilter(StringRedisTemplate redis, String adminIds, ObjectMapper json,
            int sessionTtlSeconds, int refreshThresholdSeconds) {
        this.redis = redis;
        this.admins = new HashSet<>(Arrays.asList(adminIds.trim().split("\\s*,\\s*")));
        this.json = json;
        this.sessionTtlSeconds = Math.max(1, sessionTtlSeconds);
        this.refreshThresholdSeconds = Math.max(1, Math.min(refreshThresholdSeconds, this.sessionTtlSeconds));
    }
    public static String token(HttpServletRequest request) {
        String value = request.getHeader("authorization");
        if (value == null) return null;
        if (value.startsWith("Bearer ")) value = value.substring(7);
        return value.matches("[a-fA-F0-9]{32}") ? value : null;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        UserHolder.removeUser();
        SecurityContextHolder.clearContext();
        String suppliedId = req.getHeader("X-Request-Id");
        String requestId = suppliedId != null && suppliedId.matches("[A-Za-z0-9._-]{1,64}")
                ? suppliedId : UUID.randomUUID().toString();
        MDC.put("requestId", requestId);
        res.setHeader("X-Request-Id", requestId);
        try {
            try {
                String token = token(req);
                if (token != null) {
                    String key = RedisConstants.LOGIN_USER_KEY + token;
                    List<?> values = redis.execute(READ_SESSION, List.of(key),
                            String.valueOf(sessionTtlSeconds), String.valueOf(refreshThresholdSeconds));
                    if (values != null && !values.isEmpty()) {
                        Map<String, String> data = new HashMap<>(values.size() / 2);
                        for (int index = 0; index + 1 < values.size(); index += 2) {
                            data.put(String.valueOf(values.get(index)), String.valueOf(values.get(index + 1)));
                        }
                        UserDTO user = BeanUtil.fillBeanWithMap(data, new UserDTO(), false);
                        if (user.getId() != null) {
                            var roles = new ArrayList<SimpleGrantedAuthority>();
                            roles.add(new SimpleGrantedAuthority("ROLE_USER"));
                            if (admins.contains(user.getId().toString())) roles.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                            UserHolder.saveUser(user);
                            SecurityContextHolder.getContext().setAuthentication(
                                new UsernamePasswordAuthenticationToken(user, null, roles));
                        }
                    }
                }
            } catch (DataAccessException ex) {
                res.setStatus(503);
                res.setContentType("application/json;charset=UTF-8");
            json.writeValue(res.getWriter(), Result.fail("Authentication service is temporarily unavailable"));
                return;
            }
            chain.doFilter(req, res);
        } finally {
            UserHolder.removeUser();
            SecurityContextHolder.clearContext();
            MDC.remove("requestId");
        }
    }
}
