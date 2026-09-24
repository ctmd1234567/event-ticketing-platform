package com.eventplatform.payment;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

class EventPaymentServiceTest extends PaymentServiceContract {
    @Override
    protected DataSource source() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:payment_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        for (String migration : new String[]{"V3__event_catalog.sql", "V4__event_orders.sql",
                "V5__event_order_expiry_retry.sql", "V6__event_payments.sql",
                "V7__event_notifications.sql"}) {
            String sql = new ClassPathResource("db/migration/" + migration).getContentAsString(StandardCharsets.UTF_8)
                    .replace(" CHARACTER SET ascii COLLATE ascii_bin", "")
                    .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
            if (migration.startsWith("V6__")) {
                // H2 fixture mirrors the forward V9 reason constraint; MySQL IT runs Flyway V9.
                sql = sql.replace("reason = 'LATE_PAYMENT'", "reason IN ('LATE_PAYMENT','USER_REQUEST')");
            }
            // H2 accepts one ADD COLUMN per ALTER; real MySQL IT executes V5 unchanged.
            if (migration.startsWith("V5__")) {
                sql = sql.replaceAll(",\\s+ADD COLUMN", ";\nALTER TABLE et_order ADD COLUMN");
            }
            new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(source);
        }
        return source;
    }
}
