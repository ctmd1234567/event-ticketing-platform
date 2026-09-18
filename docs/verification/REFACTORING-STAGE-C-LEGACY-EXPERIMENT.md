# Refactoring Stage C Legacy Experiment Boundary

Date: 2026-09-18

Candidate base: `6879358` (`test: separate product and legacy verification`)

Scope: `REFACTORING-CHECKLIST.md` stage C only. This record does not enter stage D, checklist item 7, or V2.

## Runtime boundary

The default application no longer creates the retained Voucher asynchronous-order processing chain. The following components require the explicit Spring profile `legacy-experiment`:

- `VoucherOrderController` and `VoucherOrderServiceImpl`;
- `OrderTransactions` and `OrderPerformance`;
- `OutboxPublisher` and `OutboxMetrics`;
- `QueueConfig` and `SeckillVoucherListener`.

`app.outbox.enabled` is false in the default configuration and true in `application-legacy-experiment.yaml` unless the experiment explicitly overrides `OUTBOX_ENABLED`. RabbitMQ connection/listener settings and Rabbit health participation are also scoped to that profile. The AMQP dependency remains in `pom.xml` because the isolated experiment and its integration test still compile and run against RabbitMQ.

Rabbit auto-configuration may still create lazy client infrastructure because the dependency is retained, but the default context has no legacy publisher, listener, topology, controller, transaction processor, or legacy metrics bean. Rabbit health is disabled by default, and the default-context test starts successfully with the Rabbit port deliberately set to the unreachable value `1`.

Global scheduling remains enabled. `ExpiredOrderCloser` and `PaymentRecoveryScanner` retain their independent default-enabled properties; stopping the legacy Outbox does not stop Event closure or payment recovery.

## Compose and launch entries

Default infrastructure contains MySQL and Redis only:

```powershell
$env:MYSQL_PASSWORD='replace-with-a-local-password'
docker compose up -d
docker compose config --services
```

The retained legacy experiment adds RabbitMQ explicitly:

```powershell
$env:MYSQL_PASSWORD='replace-with-a-local-password'
$env:RABBITMQ_PASSWORD='replace-with-a-local-password'
docker compose --profile legacy-experiment up -d
mvn '-Dspring-boot.run.profiles=local,legacy-experiment' spring-boot:run
```

`docker compose config --services` resolved to `mysql`, `redis`; adding `--profile legacy-experiment` resolved to `mysql`, `rabbitmq`, `redis`. These were configuration-only checks: no Compose service or persistent volume was started, stopped, recreated, or cleared.

## Retained k6 experiment

`loadtest/order-capacity.js` remains the minimal HTTP adapter for the historical Voucher capacity experiment. It targets `POST /voucher-order/seckill/{voucherId}`, which exists only under `legacy-experiment`. The existing isolated SQL/Lua seeds and post-run checks remain the reproduction inputs. They were not executed in stage C, and the historical 415/1500 RPS summaries were not remeasured.

This path exists to preserve engineering reproducibility until the assets are migrated or archived. It is not a default product API, an Event performance result, or authority to add new Voucher behavior.

## Verification

All Java runs used IntelliJ IDEA MCP Server 2026.1.3 with the project SDK Java 21.0.7:

- `EventInfrastructureIT`: 3 tests, IDEA exit code 0. The default context used an unreachable Rabbit port, created none of the eight legacy processing/configuration beans, retained the Event service, applied Flyway V1-V6 to isolated MySQL 8.4, and preserved the 1,000-request/100-ticket result.
- `LegacyMessagingIT`: 1 test, IDEA exit code 0. Profiles `local` and `legacy-experiment` restored the isolated MySQL/Redis/RabbitMQ Voucher reservation, Outbox publication, consumer fulfillment, idempotent replay, and stock assertions.
- `EventOrderLifecycleIT`: 2 tests started and finished with no TeamCity failure marker; the IDEA JVM exited after validating expiration scheduling, restart recovery, cancel-versus-expiry, create-versus-close, and inventory conservation. Its application launches no longer pass legacy Rabbit listener or Outbox shutdown overrides.
- `OrderTransactionsTest` (8), `OutboxPublisherTest` (3), and `ApplicationConfigurationBindingTest` (1): all three classes returned IDEA exit code 0.

Observed non-failing warnings remain the Flyway/MySQL 8.4 certification warning and MySQL's legacy decimal/floating-point `UNSIGNED` deprecation warning. The full Maven aggregate suites and k6 were not run in this stage.

## Boundaries for stage D

Stage C preserves source and tests but removes the legacy asynchronous-order chain from the default runtime. Stage D may now evaluate and remove unrelated Shop/Blog/Follow/Upload code and obsolete Voucher wrappers only after checking their remaining dependencies. It must retain an executable experiment or historical-version reproduction path for stock buckets, limits, transaction, Outbox, messaging, metrics, and load assets before deleting adapters.
