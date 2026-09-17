package com.eventplatform.payment;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

@Testcontainers
class EventPaymentServiceIT extends PaymentServiceContract {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("payment_service_acceptance");

    @Override
    protected DataSource source() {
        String url = mysql.getJdbcUrl() + (mysql.getJdbcUrl().contains("?") ? "&" : "?") + "serverTimezone=UTC";
        var source = new DriverManagerDataSource(url, mysql.getUsername(), mysql.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        return source;
    }
}
