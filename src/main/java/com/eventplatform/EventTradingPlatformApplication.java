package com.eventplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableAspectJAutoProxy(exposeProxy = true)
@SpringBootApplication(exclude=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@org.springframework.scheduling.annotation.EnableScheduling
public class EventTradingPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventTradingPlatformApplication.class, args);
    }

}
