package com.eventplatform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.eventplatform.dto.Result;
import com.eventplatform.security.TokenFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http, StringRedisTemplate redis, ObjectMapper json,
            @Value("${app.security.admin-user-ids:}") String admins,
            @Value("${app.security.session-ttl-seconds:1800}") int sessionTtlSeconds,
            @Value("${app.security.session-refresh-threshold-seconds:900}") int refreshThresholdSeconds) throws Exception {
        // Explicit header tokens only; no cookie or HTTP Basic authentication.
        http.csrf(csrf -> csrf.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(c -> c.disable()).formLogin(c -> c.disable()).httpBasic(c -> c.disable())
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.POST, "/user/login", "/user/code").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/payment-callbacks/simulated").permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/prometheus").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/events/**", "/api/v1/sessions/*/ticket-tiers").permitAll()
                .requestMatchers(HttpMethod.GET, "/shop/**", "/shop-type/**", "/voucher/list/**", "/blog/hot", "/upload/images/**").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/shop", "/shop/**", "/voucher", "/voucher/**", "/shop-type", "/shop-type/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401); res.setContentType("application/json;charset=UTF-8");
                json.writeValue(res.getWriter(), Result.fail("Authentication required"));
                })
                .accessDeniedHandler((req, res, ex) -> {
                    res.setStatus(403); res.setContentType("application/json;charset=UTF-8");
                json.writeValue(res.getWriter(), Result.fail("Access denied"));
                }))
            .addFilterBefore(new TokenFilter(redis, admins, json, sessionTtlSeconds, refreshThresholdSeconds),
                    UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
