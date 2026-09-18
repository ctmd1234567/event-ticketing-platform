package com.eventplatform.controller;

import com.eventplatform.dto.LoginFormDTO;
import com.eventplatform.dto.Result;
import com.eventplatform.dto.UserDTO;
import com.eventplatform.service.IUserService;
import com.eventplatform.utils.RedisConstants;
import com.eventplatform.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user")
public class UserController {
    @Resource
    private com.eventplatform.security.RequestLimits limits;

    @Resource
    private IUserService userService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone,
            jakarta.servlet.http.HttpServletRequest request) {
        limits.auth(request);
        return userService.sendCode(phone);
    }

    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm,
            jakarta.servlet.http.HttpServletRequest request) {
        limits.auth(request);
        return userService.login(loginForm);
    }

    @PostMapping("/logout")
    public Result logout(jakarta.servlet.http.HttpServletRequest request) {
        String token = com.eventplatform.security.TokenFilter.token(request);
        if (token != null) redisTemplate.delete(RedisConstants.LOGIN_USER_KEY + token);
        UserHolder.removeUser();
        return Result.ok();
    }

    @GetMapping("/me")
    public Result me() {
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }
}
