# Private checklist item 7 verification: V1 joint acceptance

Date: 2026-09-18

## Scope and version

- Acceptance target: private `DEVELOPMENT-CHECKLIST.md` item 7, **V1 joint acceptance and first application materials**.
- Starting commit: `612596aad8258b04109f5eb4715e8240e7cb1be3` on `main`, synchronized with local `origin/main` at the start of the audit.
- Starting tracked change: the author's existing uncommitted planning update in `REFACTORING-CHECKLIST.md`; it was preserved.
- Starting untracked work: `scripts/demo-v1.sh` plus the four Event baseline SQL/Lua/shell files. They were audited before modification.
- Scope excludes private checklist items 8-12, Maven module moves, legacy source deletion, Event inventory bucketing, admission-control migration, and RabbitMQ core ordering.
- Flyway `V1` through `V6` were not edited. Their final SHA-256 values are recorded below.

This record distinguishes historical verification from evidence produced for the current item-7 worktree. A checkbox may be completed only after its current evidence is available.

## Deliverables

- V1 demo: `scripts/demo-v1.sh`
- Event Postman collection: `postman/collections/Event-V1.postman_collection.json`
- Reused Local environment: `postman/environments/Local.environment.yaml`
- Postman Local View metadata: `.postman/resources.yaml` (registration only; it is not a second collection directory)
- Real-write runner: `loadtest/event-trading-baseline.sh`
- Scoped seed: `loadtest/seed-event-trading-baseline.sql`
- Scoped Redis sessions: `loadtest/seed-event-trading-baseline-tokens.lua`
- Final database audit: `loadtest/audit-event-trading-baseline.sql`
- Baseline result: `docs/verification/results/event-v1-baseline-20260918.md`.

## V1 joint test set discovered from source

The following are real classes in `src/test/java`; no class name is inferred from the checklist text.

### Default suite: 65 test methods

Run the Maven `test` goal from IDEA, or select these JUnit classes together:

```text
ApplicationConfigurationBindingTest
AuthCodesTest
EventCatalogServiceTest
EventOrderControllerTest
EventOrderServiceTest
LogoutControllerTest
OrderTransactionsTest
OutboxPublisherTest
PaymentCallbackControllerTest
PaymentControllerTest
PaymentRecoveryAdminControllerTest
RequestLimitsTest
SecurityRegressionTest
SimulatedPaymentGatewayTest
payment.EventPaymentServiceTest
```

Current-worktree result: **65 run, 0 failures, 0 errors, 0 skipped**. The IDEA terminal used Microsoft OpenJDK 21.0.7 and Maven 3.9.16 to run `mvn test`; Maven reported `BUILD SUCCESS` on 2026-09-18 at 23:43:02 +08:00.

### Event real-MySQL integration: 31 test methods

Run these classes separately in IDEA with Microsoft OpenJDK 21.0.7:

```text
EventInfrastructureIT       3 tests
EventOrderLifecycleIT       2 tests
payment.PaymentBoundaryIT  26 tests
```

Coverage mapping:

- `EventInfrastructureIT`: Flyway/MySQL Event round trip; default context excludes `VoucherOrderController`, `OrderTransactions`, `OutboxPublisher`, `OutboxMetrics`, `QueueConfig`, and `SeckillVoucherListener`; 1,000 distinct requests with 64 workers reserve exactly 100 of 100 tickets, produce 900 business conflicts and zero technical failures, and conserve inventory.
- `EventOrderLifecycleIT`: expiry within the test budget, restart catch-up, cancel-versus-expiry, and create-versus-close inventory conservation.
- `PaymentBoundaryIT`: normal payment; same-key replay and changed-key conflict; ownership; `UNKNOWN` after lost response; startup recovery; payment-first and close-first races; duplicate/concurrent callbacks; late-charge compensation; refund `UNKNOWN`; repeated recovery claims; no second inventory mutation.
- Default service/controller/security tests additionally cover user cancellation, invalid quantities, transaction rollback, authorization boundaries, callback forwarding, and payment/refund owner scoping.

`LegacyMessagingIT` is deliberately outside the Event V1 acceptance set. RabbitMQ is not needed to run any class above.

Current-worktree IDEA/JUnit results:

- `EventInfrastructureIT`: **3/3 passed** against Testcontainers MySQL 8.4; Flyway validated all six migrations. Its default-context assertion confirmed the legacy controller, transaction service, Outbox publisher/metrics, Rabbit configuration, and listener beans were absent. The class also completed the 1,000-request/100-ticket concurrency case with the expected 100 successes, 900 business conflicts, and zero technical failures.
- `EventOrderLifecycleIT`: **2/2 passed** against Testcontainers MySQL 8.4, including expiry/restart catch-up and deterministic close/create races.
- `PaymentBoundaryIT`: **26/26 passed** against Testcontainers MySQL 8.4; the IDEA log contains 26 `testStarted`, 26 `testFinished`, no `testFailed`, and process exit code 0. This class covers normal payment, idempotency, ownership, `UNKNOWN`, restart recovery, both close/payment winners, repeated/concurrent callbacks, late-charge compensation, and refund recovery.

Joint V1 total: **96 tests passed** (65 default + 31 Event real-MySQL integration), with 0 failures/errors/skips. The isolated `LegacyMessagingIT` was not part of this run and is not counted as Event V1 evidence.

## Demo acceptance

Command after starting the default application with the `local` profile, a 30-second demo payment window, and an allowlisted ADMIN identity:

```bash
bash scripts/demo-v1.sh
```

The script fails on the first unexpected HTTP/result value. It creates a fresh Event, Session, and three TicketTiers; logs in four independent identities; queries the published catalog; replays an order and payment idempotently; verifies owner isolation; demonstrates normal payment, user cancellation, and timeout closure; and prints final order, payment, and inventory state. It does not use fixed historical business IDs.

Status: **passed**. The final run created Event `2100934838596784130`, Session `2100934839347564545`, and three new tiers without fixed historical IDs. It produced:

- paid order `2100934843567034370`: `PAID`, CNY 8,800; payment `2100934845190230018`: `SUCCEEDED`, with no refund as expected;
- canceled order `2100934847484514305`: `CLOSED / USER_CANCELED`;
- expired order `2100934849359368194`: `CLOSED / PAYMENT_EXPIRED`;
- final inventory across the three one-ticket tiers: capacity 3, available 2, reserved 0, allocated 1.

The same run also passed order/payment idempotent replay, published-catalog reads, owned-order reads, and cross-user hiding.

## Postman acceptance

The standard Postman Collection v2.1 JSON covers:

- local identity/code acquisition and three independent tokens;
- ADMIN Event, Session, and two TicketTier creation plus publication;
- public Event and TicketTier reads;
- order create/replay/list/read/cancel;
- payment create/read/refresh and compensation-refund read/refresh;
- idempotency-key payload conflict, owner isolation, invalid V1 quantity, and non-ADMIN rejection;
- ADMIN recovery list and explicit manual retry requests.

The callback endpoint is documented by the API contract and controller tests but is not presented as a hand-callable happy path without a configured callback secret and correctly computed signature. Postman variables use `base_url`, `admin_token`, `token`, `other_token`, `eventId`, `sessionId`, `ticketTierId`, `alternateTicketTierId`, `orderId`, `cancelOrderId`, `paymentId`, and `refundId` from the existing Local environment.

Static JSON validation passed, and every `{{variable}}` reference resolves to a collection/local-environment variable. The collection contains 33 callable requests. Runtime collection execution is not a substitute for deterministic callback/race tests and is not required to manufacture every injected failure path.

## Default Event real-write baseline

Planned bounded local workload (not a capacity search):

- 120 complete-trade attempts, at most 12 concurrent workers;
- each attempt uses its own Redis identity and idempotency keys;
- measured path: authenticated Event order creation -> simulated payment -> owned order query;
- one isolated Event/Session/TicketTier with capacity 200; only those reserved IDs are reseeded;
- no database, Redis, RabbitMQ queue, or Docker volume reset;
- driver: bounded `curl` workers; k6 is version-recorded for environment completeness, so dropped iterations means missing scheduled worker results rather than a constant-arrival-rate scheduler metric.

The generated result records Git/worktree state, machine, Java/MySQL/Redis/Docker/Compose/k6 versions, preparation method, workload, concurrency, wall time, HTTP request count, accepted orders, successful payment responses, final PAID writes, business rejections, technical failures, dropped iterations, mean endpoint latency, and the final database row counts.

The SQL gate requires:

- `available + reserved + allocated = capacity` and all three fields nonnegative;
- one matching reservation for every order and a valid status pairing;
- no duplicate `(user_id, idempotency_key)` result;
- exactly one payment and one simulated provider charge effect per accepted order;
- all expected orders reach `PAID`, reservations reach `CONFIRMED`, and no refund appears in this normal-payment workload;
- `tb_order_request`, `tb_voucher_order`, and `tb_outbox_event` counts remain unchanged before/after.

The runtime must show only the default MySQL and Redis Compose services. `legacy-experiment` is not enabled. RabbitMQ, the old Outbox, and the Voucher asynchronous creation path do not participate in Event V1 core order creation.

Status: **passed** on 2026-09-18. The retained result records Java 21.0.7, MySQL 8.4.11, Redis 7.4.11, Docker 29.7.2, Compose 5.5.0, and k6 2.2.0 on WSL2/Windows (Intel Core Ultra 9 275HX, 24 logical CPUs, 15 GiB visible memory).

Measured result: 120 complete-trade attempts with 12 bounded workers in 9 seconds; 360 HTTP requests; 120 accepted order responses; 120 successful payment responses; **120 final PAID database writes**; 0 business rejections; 0 technical failures; 0 dropped worker results. Mean HTTP time was 0.018684 s for order creation, 0.035716 s for payment, and 0.007626 s for final query. This bounded curl workload records the installed k6 version but is not a k6 arrival-rate test.

Final database result: capacity 200, available 80, reserved 0, allocated 120; 120 orders/PAID orders, 120 payments/SUCCEEDED payments, 120 provider charge effects, and 0 refunds. Inventory conservation/nonnegative, reservation-order matching, duplicate-idempotency, and exact-result gates all returned 1. Legacy table counts remained `34092 / 34092 / 34092` before and after. Only MySQL and Redis were running as Compose services; RabbitMQ, legacy asynchronous ordering, and the old Outbox did not participate.

## Environment facts already verified

- Windows k6: `k6.exe v2.2.0` (`windows/amd64`).
- IDEA project JDK: Microsoft OpenJDK `21.0.7` (the exact runtime to use for the JUnit runs).
- Host: WSL2 on Windows, Intel Core Ultra 9 275HX, 24 logical CPUs, 15 GiB WSL memory visible during the audit.
- Default Compose was brought up without the `legacy-experiment` profile. An already-running legacy RabbitMQ container was stopped without removing its container, queue data, or volume; final `docker compose ps` showed only healthy MySQL 8.4 and Redis 7.4.
- GitHub About was updated and read back as: `Transactional event ticketing backend with inventory consistency, payment recovery, and compensation.`

The exact runtime versions and measurements above were copied from the retained baseline result rather than inferred from configuration.

## Acceptance status

- [x] Demo completes all required V1 paths.
- [x] 65 default tests pass on the current worktree.
- [x] 31 Event real-MySQL integration tests pass on the current worktree.
- [x] Concurrency, idempotency conflict/replay, authorization, cancellation, timeout/restart, normal payment, `UNKNOWN`, race winners, duplicate callbacks, and late-payment compensation all pass through the mapped tests.
- [x] Default-context test proves no legacy order-processing beans are created.
- [x] Real-write baseline passes its HTTP and final-database gates.
- [x] Callable Event Postman collection and Local variables exist.
- [x] README main architecture and product narrative contain only the Event V1 path; the high-throughput experiment is a late single-line evidence link.
- [x] GitHub About matches the product.
- [x] CI and complete OpenAPI remain explicitly documented as unimplemented.

Item 7 is **accepted**, so the private checklist's V1/Core Trading completion point (items 0-7) has been reached. This is a local development acceptance, not a production SLA, HA, soak, or deployment certification. CI and complete OpenAPI remain unimplemented; Postman is the callable request artifact. The historical Voucher 1,500 RPS experiment is an isolated engineering write path and **does not represent Event performance**. RabbitMQ does **not** participate in V1 core order creation.

## Flyway V1-V6 final SHA-256

```text
e6a4328b9a02cee3f26be848a7bc41f6d796b7f175122a323cfbc8b7376b1805  V1__legacy_base.sql
23c4b90e3594ab4faba7f343c656937908be15c3012816f72ac9313d130b6750  V2__legacy_order_outbox.sql
b94812aa71dc4fe1c06c852d49286a2df84663b40cafdc85fafba46a3d714ddb  V3__event_catalog.sql
eb93fc9e98b882784442629bbfcec697774c49846a5dfc6b7ab6c889f434deab  V4__event_orders.sql
ffe5647c4ccd0bd98eebd119104e5ee8c16bffa72c53a1d2af80a1575f07f522  V5__event_order_expiry_retry.sql
40f69a24fc19f06caebc687083a45d0a094b6b0d73cdd21cebfcba3ea1a32fd8  V6__event_payments.sql
```
