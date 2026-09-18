# Refactoring Stage B Test Boundaries

Date: 2026-09-18

Candidate base: 9a84d8425e49e302594a3ca1d483128103401e41

Scope: REFACTORING-CHECKLIST.md stage B only.

## Ownership after the split

- EventInfrastructureIT owns Flyway V1-V6 on empty MySQL, a real Event catalog/order round trip, and the 1,000-request/100-ticket invariant. It starts only MySQL and disables unrelated legacy Outbox/Rabbit listener and recovery schedulers for this focused context.
- LegacyMessagingIT owns isolated Redis/RabbitMQ connectivity and the retained Voucher reservation, Outbox publication, consumer fulfillment, idempotent re-fulfillment, and bucket-stock assertion.
- event-infrastructure-seed.sql contains only Event catalog/session/tier rows.
- legacy-messaging-seed.sql contains only the legacy shop/user/Voucher/bucket rows needed by the message experiment.
- LogoutControllerTest owns the logout Redis-revocation and thread-local cleanup assertion formerly hidden in VoucherOrderControllerTest.
- LegacyCacheCompatibilityTest owns the old merchant logical-expiry serialization assertion.
- MybatisCompatibilityTest owns the MyBatis-Plus pagination and SQL-parser compatibility assertion.
- ApplicationConfigurationBindingTest owns Boot 3 Redis/JDBC configuration binding.

No assertion was intentionally deleted. The former mixed InfrastructureIT method was divided at its Event-versus-message boundary; its Flyway, Event, Redis, RabbitMQ, Voucher, idempotency, and stock assertions all have explicit destinations.

## Retained engineering tests

- OrderTransactionsTest remains a default unit/in-memory integration test for the legacy short transaction, rollback, idempotency, batches, stock buckets, and concurrency behavior.
- OutboxPublisherTest remains a default unit test for broker failure, Confirm/Return interpretation, retry retention, and batched send-before-wait behavior.
- RequestLimitsTest remains a default unit test for the retained multi-key Voucher admission algorithm. It does not claim that those keys or limits are Event policy.
- AuthCodesTest, SecurityRegressionTest, and LogoutControllerTest remain the focused identity/security verification set.

These tests protect engineering assets. Their inclusion in the default test suite does not make the old Voucher chain part of the final Event product.

## Verification entries

- Default non-manual suite: mvn test
- Focused Event infrastructure: mvn -Pinfrastructure -Dit.test=EventInfrastructureIT verify
- Focused legacy message experiment: mvn -Pinfrastructure -Dit.test=LegacyMessagingIT verify
- Full non-manual infrastructure suite: mvn -Pinfrastructure verify
- Focused default classes in IDEA: OrderTransactionsTest, OutboxPublisherTest, RequestLimitsTest, AuthCodesTest, SecurityRegressionTest, LogoutControllerTest, LegacyCacheCompatibilityTest, MybatisCompatibilityTest, and ApplicationConfigurationBindingTest.

The manual JUnit tag remains excluded from both Surefire and Failsafe. None of these entries runs the untracked demo or load-test scripts or writes the development database.

## Acceptance status

Stage B was verified on 2026-09-18 against the uncommitted candidate based on `9a84d8425e49e302594a3ca1d483128103401e41`. IntelliJ IDEA MCP Server 2026.1.3 launched every class with the project SDK (Java 21.0.7); every run returned exit code 0:

- `EventInfrastructureIT`: 2 tests. An isolated MySQL 8.4 container validated and applied Flyway V1-V6, completed the Event catalog/order round trip, and preserved the 1,000-request/100-ticket invariant.
- `LegacyMessagingIT`: 1 test. Isolated MySQL 8.4, Redis 7.4 and RabbitMQ 4.1 containers completed the retained legacy messaging, idempotency and stock assertions.
- Nine focused default classes: 22 tests across `OrderTransactionsTest` (8), `OutboxPublisherTest` (3), `RequestLimitsTest` (1), `AuthCodesTest` (3), `SecurityRegressionTest` (3), `LogoutControllerTest` (1), `LegacyCacheCompatibilityTest` (1), `MybatisCompatibilityTest` (1), and `ApplicationConfigurationBindingTest` (1).

Observed non-failing infrastructure warnings: the bundled Flyway version reports MySQL 8.4 as newer than its latest tested MySQL 8.1, and MySQL reports deprecated `UNSIGNED` use for decimal/floating-point columns in the legacy migration. These are recorded limitations, not newly inferred failures.

The full `mvn test` and `mvn -Pinfrastructure verify` aggregate commands were not separately run in this verification pass; the checklist-required split integration, transaction/Outbox, identity/security and configuration classes were run directly through IDEA. No untracked demo/load-test script was executed and no development database was used.
