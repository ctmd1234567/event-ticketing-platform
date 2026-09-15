# Checklist 0-5 Verification Record

Date: 2026-09-15

Branch: `main`

Scope: development checklist sections 0 through 5

## 0. Current-state inventory

Status: complete and verified.

- The repository, branch, tracked modifications, untracked additions, architecture documents, migrations, core order code, security configuration, and tests were inspected.
- Existing voucher ordering remains a compatibility path. The event catalog and synchronous event-order path are a separate V1 increment.
- The current worktree passed 38 default tests with Microsoft OpenJDK 21.0.7 and Maven 3.9.16.
- The commands used the JDK already configured as IDEA project SDK `ms-21`; no persistent JDK or Maven configuration was changed.

## 1. Frozen business rules and minimum design

Status: documented; runtime-independent review complete.

- `docs/architecture/DOMAIN-MODEL.md` fixes the V1 scope, one-tier/one-ticket order rule, CNY integer-fen money, immutable capacity after publication, inventory conservation, purchase limit, idempotency policy, refund inventory policy, late-payment compensation, and the three local MySQL transaction boundaries.
- `docs/architecture/STATE-MACHINES.md` defines Order, Payment, Refund, and InventoryReservation states, including `UNKNOWN` and close-versus-payment race policy.
- `docs/architecture/API-CONTRACT.md` distinguishes the implemented cancellation lifecycle from later payment, refund, notification, and operations targets.

## 2. Runnable minimum engineering baseline

Status: complete and verified.

- Java 21, Spring Boot 3.5.16, MySQL 8.4, Redis 7.4, RabbitMQ 4.1, Maven, and Flyway versions/commands are recorded.
- Docker Compose starts only the required dependencies. Historical destructive bootstrap scripts are no longer mounted.
- Flyway migrations `V1` through `V5` cover the legacy schema, legacy order/Outbox additions, event catalog, event orders, and persisted expiry-retry state.
- Existing non-empty databases baseline at version 2 only after the documented schema prerequisite; Flyway clean is disabled.
- Test seed data is isolated under `src/test/resources`.
- `InfrastructureIT` uses Testcontainers and checks Flyway, real MySQL event ordering and concurrency, Redis, RabbitMQ, and the retained voucher path.

Acceptance commands executed with the IDEA project JDK:

```text
mvn test
mvn -Pinfrastructure verify
```

Latest results: 38 default tests passed with no failures, errors, or skips. `InfrastructureIT` was also run directly by IDEA and both tests exited with code 0. Flyway applied `V1` through `V5` to a real MySQL 8.4 empty database. Flyway 11.7.2 emitted a database-version certification warning, recorded as a known limitation; migrations and assertions passed.

## 3. Event catalog, ticket tiers, and basic authorization

Status: complete and verified.

- Admin-only endpoints create an event, add sessions and ticket tiers, publish, and stop sales.
- Public endpoints list published events, return event/session details, and show ticket-tier availability.
- Synchronous event order creation validates resource existence, publication/on-sale state, the half-open sales window, CNY, quantity 1, and available inventory under a row lock.
- The server stores immutable unit-price and total-price snapshots. The order and independent `RESERVED` inventory reservation commit in one local transaction.
- Same-user/same-key replay returns the original order; changed payload or a second key for the same user/tier is rejected.
- Order reads are filtered by authenticated user and return 404 for another user's order.
- Unit tests cover missing resources, invalid session times, negative price, wrong currency, invalid quantity, future/expired/stopped sales, idempotency conflict, purchase limit, inventory conservation, and ownership.
- Security regression tests cover public catalog reads and denial of admin mutations to ordinary users.

During section 3 acceptance, the packaged application was also started against the persistent Compose stack with the IDEA project SDK, Microsoft OpenJDK 21.0.7. Flyway then confirmed schema version `4`; ports 8081 and 8082 listened successfully, the public event endpoint returned HTTP 200, and the health endpoint returned `UP`. The later isolated section 5 run verified migration through `V5`.

## 4. Synchronous ordering, inventory reservation, and idempotency

Status: complete and verified.

- Event order creation commits the conditional inventory change, order, and independent reservation in one local MySQL transaction; it does not depend on RabbitMQ.
- `available>=quantity` in the guarded update, the locked ticket-tier row, database checks, and unique constraints prevent negative inventory and duplicate orders.
- Same-user/same-key/same-payload retries return the original order. A changed payload conflicts, while a different key is governed separately by the one-order-per-user-and-tier purchase limit.
- A forced reservation-insert failure verifies that the preceding inventory update and order insert both roll back.
- A real MySQL 8.4 Testcontainers test submits 1,000 distinct valid requests through 64 concurrent workers against capacity 100, without HTTP rate-limit interference or payment/expiry processing.
- All 1,000 requests completed inside the 90-second budget: 100 reservations succeeded, 900 requests received explicit business conflicts, and zero requests had technical failures.
- Final assertions verified `capacity=100`, `available=0`, `reserved=100`, `allocated=0`, 100 pending-payment orders, 100 `RESERVED` reservation rows, and reserved quantity sum 100.
- The Testcontainers JDBC connection is explicitly pinned to UTC so the sale-window fixture uses the same timestamp semantics as the documented local runtime URL.

## 5. Cancellation, timeout closure, inventory release, and restart recovery

Status: complete and verified.

- `POST /api/v1/orders/{orderId}/cancel` closes only the authenticated user's unpaid order. Another user's order remains hidden as 404, and a non-pending order returns 409.
- Cancellation and expiration share one local MySQL transaction. It locks rows in the fixed order `order -> inventory reservation -> ticket tier`, changes `PENDING_PAYMENT` to `CLOSED`, changes `RESERVED` to `RELEASED`, and moves the quantity from `reserved` back to `available`.
- A repeated cancellation or expiry scan observes the terminal `CLOSED` order and performs no additional release. The V1 one-order-per-user-and-tier purchase limit remains in force after cancellation.
- Expiration candidates come from the indexed `(status, payment_deadline)` database query using `CURRENT_TIMESTAMP`; the scheduler does not depend on an in-memory deadline queue.
- A failed candidate records its attempt count, next-attempt timestamp, and bounded error detail. The persisted retry delay prevents one malformed order from occupying every small batch and starving later eligible orders.
- The payment window, scan interval, batch size, scan enable switch, and failure retry delay are configurable. Production defaults remain 900 seconds, 1 second, 200 orders, enabled scanning, and a 30-second failure delay; acceptance overrides the payment window to 30 seconds and disables the scheduler only in the deterministic concurrency test.
- `ApplicationReadyEvent` triggers an immediate recovery scan, while the same persistent query continues on the configured fixed delay.
- Unit tests cover ownership, repeated cancellation, repeated expiry scans, exact reservation release, inventory conservation, purchase-limit retention, a paid-order-wins state that cannot be canceled or released, and poison-order retry without starvation. A controller test verifies that the cancel route forwards the authenticated actor rather than accepting a caller-supplied user identity.
- The complete default suite passed 38 tests with Microsoft OpenJDK 21.0.7 and Maven 3.9.16, with no failures, errors, or skips.
- `EventOrderLifecycleIT` was then run directly by IDEA JUnit with the same Java 21 SDK. Its two test methods used an isolated MySQL 8.4 Testcontainer, applied Flyway `V1` through `V5`, and the class exited with code 0.
- With TTL 30 seconds and scan interval 1 second, the healthy application closed and released the test order at the first eligible scan, within the 10-second post-deadline budget.
- The real-MySQL cancel-versus-expiry race left one `CLOSED` order, one `RELEASED` reservation, and exactly conserved inventory, regardless of which close reason won.
- A second real-MySQL race created a new reservation on the same tier while an expired reservation was being released; the final state retained one pending reservation and exactly conserved the tier's inventory.
- Four additional orders remained pending while the first application context was stopped across their deadlines. After the second context became ready, it closed and released all four in approximately 43 ms, within the 30-second restart budget.
- Final assertions found no reserved rows for closed test orders and verified each closed order had its corresponding released reservation and `available + reserved + allocated = capacity`.

`InfrastructureIT` was additionally run directly by IDEA with the same SDK. Both tests exited with code 0, connected to MySQL 8.4, Redis, and RabbitMQ, verified Flyway `V1` through `V5`, and retained the 1,000-request result of 100 reservations, 900 explicit business conflicts, and zero technical failures.

The direct IDEA run is the current lifecycle evidence; it does not generate a Maven Failsafe report. Flyway 11.7.2 still warns that MySQL 8.4 is newer than its certified range, while all migrations and assertions passed. These timing budgets are test criteria, not production SLAs. A real payment callback race remains section 6 scope; section 5 verifies that an already paid order cannot be canceled or released.

## Remaining acceptance boundary

Sections 0 through 5 have passed their current automated, Testcontainers, and persistent-stack checks. Section 6 remains incomplete: simulated payment boundaries, `UNKNOWN`, payment-versus-close resolution, and late-payment compensation have not been claimed.
