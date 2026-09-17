package com.eventplatform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.eventplatform.config.SecurityConfig;
import com.eventplatform.dto.UserDTO;
import com.eventplatform.security.TokenFilter;
import com.eventplatform.utils.UserHolder;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=SecurityRegressionTest.Probe.class,properties="app.security.admin-user-ids=1",
    excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfig.class,SecurityRegressionTest.Probe.class})
class SecurityRegressionTest {
    @MockitoBean StringRedisTemplate redis;
    @Autowired MockMvc mvc;
    static final String ADMIN="a".repeat(32), USER="b".repeat(32);
    @BeforeEach void setup() {
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    List<String> keys=invocation.getArgument(1);
                    if (keys.equals(List.of("login:token:"+ADMIN))) return List.of("id","1");
                    if (keys.equals(List.of("login:token:"+USER))) return List.of("id","2");
                    return List.of();
                });
    }
    @RestController static class Probe {
        @GetMapping("/shop/1") String publicShop() { return "shop"; }
        @PostMapping("/shop") String writeShop() { return "written"; }
        @PutMapping("/shop") String updateShop() { return "updated"; }
        @PostMapping("/voucher/seckill") String voucher() { return "created"; }
        @GetMapping("/user/me") Long me() { return UserHolder.getUser().getId(); }
        @GetMapping("/api/v1/events") String events() { return "events"; }
        @PostMapping("/api/v1/admin/events") String createEvent() { return "created"; }
        @PostMapping("/api/v1/payment-callbacks/simulated") String callback() { return "accepted"; }
        @PostMapping("/api/v1/payment-callbacks/other") String otherCallback() { return "wrong"; }
    }
    @Test void publicReadsAndAdminOnlyWrites() throws Exception {
        mvc.perform(get("/shop/1")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/events")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/payment-callbacks/simulated")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/payment-callbacks/other")).andExpect(status().isUnauthorized());
        mvc.perform(post("/shop")).andExpect(status().isUnauthorized());
        mvc.perform(put("/shop").header("authorization",USER)).andExpect(status().isForbidden());
        mvc.perform(post("/voucher/seckill").header("authorization",USER)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/events").header("authorization",USER)).andExpect(status().isForbidden());
        mvc.perform(post("/shop").header("authorization","Bearer "+ADMIN)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/events").header("authorization","Bearer "+ADMIN)).andExpect(status().isOk());
    }
    @Test void reusedRequestThreadNeverInheritsIdentity() throws Exception {
        mvc.perform(get("/user/me").header("authorization",USER)).andExpect(status().isOk()).andExpect(content().string("2"));
        assertThat(UserHolder.getUser()).isNull();
        mvc.perform(get("/user/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/user/me").header("authorization","c".repeat(32))).andExpect(status().isUnauthorized());
    }
    @Test void exceptionAlsoClearsIdentity() {
        TokenFilter filter=new TokenFilter(redis,"1",new ObjectMapper(),1800,900);
        MockHttpServletRequest request=new MockHttpServletRequest();
        request.addHeader("authorization",USER);
        assertThatThrownBy(() -> filter.doFilter(request,new MockHttpServletResponse(),(req,res) -> {
            assertThat(UserHolder.getUser().getId()).isEqualTo(2);
            throw new ServletException("test");
        })).isInstanceOf(ServletException.class);
        assertThat(UserHolder.getUser()).isNull();
    }
}
