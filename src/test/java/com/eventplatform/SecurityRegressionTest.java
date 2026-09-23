package com.eventplatform;

import com.eventplatform.config.SecurityConfig;
import com.eventplatform.controller.EventNotificationController;
import com.eventplatform.notification.EventNotificationService;
import com.eventplatform.security.TokenFilter;
import com.eventplatform.utils.UserHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {SecurityRegressionTest.Probe.class, EventNotificationController.class},
        properties = "app.security.admin-user-ids=1",
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfig.class, SecurityRegressionTest.Probe.class})
class SecurityRegressionTest {
    @MockitoBean
    StringRedisTemplate redis;
    @MockitoBean
    EventNotificationService notifications;

    @Autowired
    MockMvc mvc;

    static final String ADMIN = "a".repeat(32);
    static final String USER = "b".repeat(32);

    @BeforeEach
    void setup() {
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                anyList(), any(Object[].class))).thenAnswer(invocation -> {
                    List<String> keys = invocation.getArgument(1);
                    if (keys.equals(List.of("login:token:" + ADMIN))) return List.of("id", "1");
                    if (keys.equals(List.of("login:token:" + USER))) return List.of("id", "2");
                    return List.of();
                });
    }

    @RestController
    static class Probe {
        @GetMapping("/user/me")
        Long me() {
            return UserHolder.getUser().getId();
        }

        @GetMapping("/api/v1/events")
        String events() {
            return "events";
        }

        @PostMapping("/api/v1/admin/events")
        String createEvent() {
            return "created";
        }

        @PostMapping("/api/v1/payment-callbacks/simulated")
        String callback() {
            return "accepted";
        }

        @PostMapping("/api/v1/payment-callbacks/other")
        String otherCallback() {
            return "wrong";
        }
    }

    @Test
    void publicReadsAndCallbacksKeepAdminWritesProtected() throws Exception {
        mvc.perform(get("/api/v1/events")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/payment-callbacks/simulated")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/payment-callbacks/other")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/events")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/events").header("authorization", USER))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/events").header("authorization", "Bearer " + ADMIN))
                .andExpect(status().isOk());
    }

    @Test
    void reusedRequestThreadNeverInheritsIdentity() throws Exception {
        mvc.perform(get("/user/me").header("authorization", USER))
                .andExpect(status().isOk()).andExpect(content().string("2"));
        assertThat(UserHolder.getUser()).isNull();
        mvc.perform(get("/user/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/user/me").header("authorization", "c".repeat(32)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void notificationRouteRequiresIdentityAndUsesItsOwnerScope() throws Exception {
        when(notifications.list(2L)).thenReturn(List.of(new EventNotificationService.NotificationView(
                1L, "ORDER_PAID:10", 10L, "ORDER_PAID", "Paid", java.time.Instant.now())));
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/notifications").header("authorization", USER))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data[0].eventId").value("ORDER_PAID:10"));
        org.mockito.Mockito.verify(notifications).list(2L);
        org.mockito.Mockito.verify(notifications, org.mockito.Mockito.never()).list(1L);
    }

    @Test
    void exceptionAlsoClearsIdentity() {
        TokenFilter filter = new TokenFilter(redis, "1", new ObjectMapper(), 1800, 900);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("authorization", USER);
        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertThat(UserHolder.getUser().getId()).isEqualTo(2);
            throw new ServletException("test");
        })).isInstanceOf(ServletException.class);
        assertThat(UserHolder.getUser()).isNull();
    }
}
