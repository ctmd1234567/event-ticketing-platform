package com.eventplatform.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eventplatform.dto.LoginFormDTO;
import com.eventplatform.dto.Result;
import com.eventplatform.dto.UserDTO;
import com.eventplatform.entity.User;
import com.eventplatform.mapper.UserMapper;
import com.eventplatform.service.IUserService;
import com.eventplatform.utils.RedisConstants;
import com.eventplatform.utils.RegexUtils;
import com.eventplatform.utils.SystemConstants;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class UserServiceImpl implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private com.eventplatform.security.AuthCodes authCodes;

    @Resource
    private UserMapper users;

    @Override
    public Result sendCode(String phone) {
        if (RegexUtils.isPhoneInvalid(phone)) return Result.fail("Invalid phone number");
        return authCodes.send(phone);
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        String code = loginForm.getCode();
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) return Result.fail("Invalid phone number");
        if (!authCodes.consume(phone, code)) return Result.fail("Invalid verification code");

        User user = findByPhone(phone);
        if (user == null) {
            try {
                user = createUserWithPhone(phone);
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                user = findByPhone(phone);
                if (user == null) throw duplicate;
            }
        }

        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> map = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        var arguments = new java.util.ArrayList<String>();
        map.forEach((key, value) -> {
            arguments.add(key);
            arguments.add(value.toString());
        });
        var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                "redis.call('HSET',KEYS[1],unpack(ARGV)); redis.call('EXPIRE',KEYS[1],1800); return 1",
                Long.class);
        stringRedisTemplate.execute(script, java.util.List.of(tokenKey), arguments.toArray());
        return Result.ok(token);
    }

    private User findByPhone(String phone) {
        return users.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        users.insert(user);
        return user;
    }
}
