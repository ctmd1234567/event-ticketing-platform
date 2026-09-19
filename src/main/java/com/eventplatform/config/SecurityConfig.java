package com.eventplatform.config;

import com.eventplatform.dto.Result;
import com.eventplatform.security.TokenFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http, StringRedisTemplate redis, ObjectMapper json,
            @Value("${app.security.admin-user-ids:}") String admins,
            @Value("${app.security.session-ttl-seconds:1800}") int sessionTtlSeconds,
            @Value("${app.security.session-refresh-threshold-seconds:900}") int refreshThresholdSeconds)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, "/user/login", "/user/code").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/payment-callbacks/simulated").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/events/**", "/api/v1/sessions/*/ticket-tiers").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            json.writeValue(response.getWriter(), Result.fail("Authentication required"));
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType("application/json;charset=UTF-8");
                            json.writeValue(response.getWriter(), Result.fail("Access denied"));
                        }))
                .addFilterBefore(new TokenFilter(redis, admins, json,
                                sessionTtlSeconds, refreshThresholdSeconds),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
