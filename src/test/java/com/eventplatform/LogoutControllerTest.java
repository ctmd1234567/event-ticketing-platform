package com.eventplatform;

import com.eventplatform.controller.UserController;
import com.eventplatform.utils.UserHolder;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LogoutControllerTest {
    @Test void logoutRevokesRedisToken() {
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        UserController controller=new UserController();
        ReflectionTestUtils.setField(controller,"redisTemplate",redis);
        MockHttpServletRequest request=new MockHttpServletRequest();
        String token="a".repeat(32);
        request.addHeader("authorization","Bearer "+token);
        assertThat(controller.logout(request).getSuccess()).isTrue();
        verify(redis).delete("login:token:"+token);
        assertThat(UserHolder.getUser()).isNull();
    }
}
