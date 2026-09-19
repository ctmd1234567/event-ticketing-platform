# Event V1 verification record

Date: 2026-09-19

## Scope

This is a bounded local verification of the current V1 Event trading flow: catalog publication, synchronous order and inventory reservation, payment/recovery boundaries, lifecycle closure, and public HTTP demonstration. It is not a production SLA, capacity limit, soak test, high-availability certification, or V2 implementation.

## Automated verification

The project uses Microsoft OpenJDK 21.0.7 and Maven 3.9.16 for this record.

```bash
mvn test
mvn -Pinfrastructure verify
```

- Default suite: 65 tests, 0 failures, 0 errors, 0 skipped.
- Infrastructure profile: 58 Testcontainers integration tests across `EventInfrastructureIT` (3), `EventOrderLifecycleIT` (2), `EventPaymentServiceIT` (26), `PaymentBoundaryIT` (26), and the isolated `LegacyMessagingIT` (1).
- Event V1 integration scope is 57 tests; `LegacyMessagingIT` verifies the optional historical RabbitMQ experiment and is not part of Event order creation.
- The integration suite applies all six Flyway migrations to isolated MySQL 8.4 containers. RabbitMQ is not required by the Event V1 test classes.

`EventOrderLifecycleIT` starts a non-web Spring context because it calls services and JDBC directly. Servlet security configuration remains conditional on a servlet application; this avoids opening an unnecessary HTTP listener in the lifecycle test and leaves production web security unchanged.

## HTTP demo

With MySQL and Redis running, start the application under the `local` profile with an allowlisted administrator:

```bash
mvn -Dspring-boot.run.profiles=local spring-boot:run
bash scripts/demo-v1.sh
```

The script creates a fresh Event, Session, and one-ticket tier. It then proves public catalog reads, authenticated order creation, same-key order replay, `PENDING_PAYMENT` plus reserved inventory, successful payment, same-key payment replay, and the final `PAID / SUCCEEDED / allocated=1` state. Every request and invariant is checked; the script stops at the first mismatch. It does not depend on fixed historical business IDs.

## Event order creation baseline

The retained baseline is [event-order-creation-baseline-20260919.md](results/event-order-creation-baseline-20260919.md). It is an order-only workload against the formal `POST /api/v1/orders` path:

- 200 authenticated requests, 32 bounded concurrent workers, ticket capacity 100;
- 100 successful reservations, 100 explicit business conflicts, 0 technical failures, 0 dropped attempts;
- all-request latency: P50 0.012996 s, P95 0.040840 s, P99 0.048521 s;
- final inventory: capacity 100, available 0, reserved 100, allocated 0;
- 100 orders, 100 distinct buyers, and 100 matching reservations;
- oversold count 0; conservation, nonnegative inventory, relationship, duplicate, and exact-result gates all passed;
- the historical Voucher/Outbox tables were unchanged.

The runner seeds only its isolated Event, tier, identities, and Redis sessions. It does not reset databases, Redis, Docker volumes, or unrelated business data. The result is a bounded local baseline, not a capacity search or payment benchmark.

## CI

`.github/workflows/ci.yml` runs on pushes to `main` and pull requests. It uses Java 21 with Maven dependency caching and has separate jobs for:

- `mvn --batch-mode --no-transfer-progress test`
- `mvn --batch-mode --no-transfer-progress -Pinfrastructure verify`

The workflow definition is present locally; a green GitHub-hosted run can only be claimed after the changes are committed and pushed.

## Known boundaries

- Flyway 11.7.2 warns that MySQL 8.4 is newer than its latest certified MySQL version (8.1). The migrations and assertions pass, but this is not a production compatibility certification.
- Mockito currently emits a future-JDK dynamic-agent warning.
- User-initiated refunds, Event notifications/SSE, Event Outbox/DLQ, generalized reconciliation, complete OpenAPI, deployment, and V2 work remain outside this scope.
- The separate Voucher/RabbitMQ 1,500 RPS experiment is historical engineering evidence and does not represent Event throughput, production SLA, or long-run stability.
