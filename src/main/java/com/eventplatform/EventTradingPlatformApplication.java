package com.eventplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@org.springframework.scheduling.annotation.EnableScheduling
public class EventTradingPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventTradingPlatformApplication.class, args);
    }

}
