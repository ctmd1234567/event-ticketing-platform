package com.eventplatform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationConfigurationBindingTest {
    @Test
    void redisAndJdbcConfigurationBindWithBoot3() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"))
                .forEach(source -> environment.getPropertySources().addFirst(source));

        RedisProperties redis = Binder.get(environment)
                .bind("spring.data.redis", RedisProperties.class)
                .get();
        assertThat(redis.getLettuce().getPool().getMaxActive()).isEqualTo(30);
        assertThat(environment.getProperty("spring.redis.host")).isNull();

        DataSourceProperties datasource = Binder.get(environment)
                .bind("spring.datasource", DataSourceProperties.class)
                .get();
        assertThat(datasource.getDriverClassName()).isEqualTo("com.mysql.cj.jdbc.Driver");
        assertThat(Class.forName(datasource.getDriverClassName())).isNotNull();
    }
}
