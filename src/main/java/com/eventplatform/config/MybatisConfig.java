package com.eventplatform.config;

import com.eventplatform.mapper.UserMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan(basePackageClasses = UserMapper.class)
public class MybatisConfig {
}
