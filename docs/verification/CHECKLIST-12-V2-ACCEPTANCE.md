# V2 acceptance: engineering closeout

V1 synchronous Event trading and V2 items 8–11 are complete. This record links the current implementation to its verification evidence. It does not claim a production capacity limit or disaster recovery guarantee.

- [V1 verification](V1-VERIFICATION.md) covers order creation, inventory conservation, idempotency, expiry, payment uncertainty, and late-charge compensation.
- [Item 8](CHECKLIST-8-EVENT-NOTIFICATIONS.md) and [item 9](CHECKLIST-9-MQ-RECOVERY.md) cover Event notification Outbox, publisher Confirm/Return, consumer deduplication, broker outage, DLQ, and audited redrive. RabbitMQ never creates the core order.
- [Item 10](CHECKLIST-10-REFUND-RECONCILIATION.md) covers full refunds and targeted reconciliation, including manual handling where gateway outcomes remain uncertain.
- [Item 11](CHECKLIST-11-SQL-LOAD-DEPENDENCIES.md) contains SQL plans, bounded Normal/Stress/Spike measurements, dependency failures, restart recovery, and raw result links. The measured workload did not justify Event stock buckets or a new admission layer.
- [API contract](../architecture/API-CONTRACT.md), Flyway V1–V10, the CI workflow, and its test-report gate are the engineering entry points. Request IDs correlate logs; HTTP, JVM, connection pool, Event write, Outbox, and Rabbit queue metrics are exposed for observation.

The [Voucher archive](../history/LEGACY-VOUCHER-ARCHIVE.md) explains the retirement of its runtime code and the data-preserving V10 rename. Event trading, notification, authentication, and payment code remain in the current application. V1–V9 migrations are unchanged.

## Verification boundary

The item 11 record supplies measured Event behavior and failure evidence. The integrated code passed `mvn --batch-mode --no-transfer-progress clean test` (64 Surefire tests) and `mvn --batch-mode --no-transfer-progress clean -Pinfrastructure verify` (64 Surefire plus 83 Failsafe tests) on 2026-09-25 with Java 21.0.7, Maven 3.9.16, isolated MySQL 8.4, and RabbitMQ 4.1. Both commands exited 0; all 147 tests had zero failures, errors, and skips. The report gate found all four required default and four required integration suites in fresh XML reports. CI runs these same commands and checks. The EventInfrastructureIT suite verified Flyway V1–V10 and all six renamed archive tables. Short tests, simulated payment, and a single persistent broker do not establish a production SLA, real payment integration, or zero data loss. Long soak, backup restore, SSE, and full OpenAPI remain optional work outside V2 acceptance.
