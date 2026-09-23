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
EVENT_ORDER_PAYMENT_WINDOW_SECONDS=30 mvn -Dspring-boot.run.profiles=local spring-boot:run
bash scripts/demo-v1.sh
```

The script creates a fresh Event, Session, and three one-ticket tiers for independent payment, cancellation, and expiry paths. It checks public catalog reads, authenticated order and payment replay, ownership isolation, `PAID` and `CLOSED` terminal states, close reasons, and final inventory conservation (`capacity=3`, `available=2`, `reserved=0`, `allocated=1`). The 30-second payment window is for this local demonstration; the script polls expiry for up to 45 seconds and fails if the running application uses a longer window. It does not depend on historical business IDs.


## 2026-09-23 demo scope restoration

The current candidate starts from `5337650cb52911c1a11f17caf89a3d287dfccbc3` and restores cancellation and expiry to `scripts/demo-v1.sh`; the 2026-09-19 automated test and load figures above are historical evidence for the earlier candidate, not new runs.

- IntelliJ IDEA 2026.1.3 started the Java 21.0.7 application under the `local` profile with `EVENT_ORDER_PAYMENT_WINDOW_SECONDS=30` and an allowlisted local administrator. Existing MySQL 8.4 and Redis 7.4 Compose containers were started without recreating volumes.
- `bash -n scripts/demo-v1.sh` and `git diff --check` passed. `bash scripts/demo-v1.sh` exited 0 against the live application after all nine steps. The demo used a fresh Event `2102705838023880706` and three one-ticket tiers.
- HTTP final states: one `PAID` order and `SUCCEEDED` payment for 8800 CNY fen; two `CLOSED` orders with `USER_CANCELED` and `PAYMENT_EXPIRED`. Ownership denial and order/payment replay checks passed.
- Read-only MySQL audit for that Event: three orders in those states; reservation states were one `CONFIRMED` and two `RELEASED`; ticket totals were `capacity=3`, `available=2`, `reserved=0`, `allocated=1`; the paid order had one `SUCCEEDED` payment for 8800 fen.
- This was a bounded local HTTP demonstration. The Java test suites and order load baseline were not rerun for this shell/documentation-only change.

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
