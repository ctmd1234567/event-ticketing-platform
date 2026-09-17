package com.eventplatform.payment;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

/** Checklist item 6 acceptance against the real MySQL locking and constraint behavior. */
@Testcontainers
class PaymentBoundaryIT extends PaymentServiceContract {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("payment_boundary_acceptance");

    @Override
    protected DataSource source() {
        String url = mysql.getJdbcUrl() + (mysql.getJdbcUrl().contains("?") ? "&" : "?") + "serverTimezone=UTC";
        var source = new DriverManagerDataSource(url, mysql.getUsername(), mysql.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        return source;
    }
}
