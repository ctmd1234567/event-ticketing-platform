# Checklist 0-4 Verification Record

Date: 2026-09-14

Branch: `main`

Scope: development checklist sections 0 through 4

## 0. Current-state inventory

Status: complete and verified.

- The repository, branch, tracked modifications, untracked additions, architecture documents, migrations, core order code, security configuration, and tests were inspected.
- Existing voucher ordering remains a compatibility path. The event catalog and synchronous event-order path are a separate V1 increment.
- The current worktree passed 33 default tests with Microsoft OpenJDK 21.0.7 and Maven 3.9.16.
- The commands used the JDK already configured as IDEA project SDK `ms-21`; no persistent JDK or Maven configuration was changed.

## 1. Frozen business rules and minimum design

Status: documented; runtime-independent review complete.

- `docs/architecture/DOMAIN-MODEL.md` fixes the V1 scope, one-tier/one-ticket order rule, CNY integer-fen money, immutable capacity after publication, inventory conservation, purchase limit, idempotency policy, refund inventory policy, late-payment compensation, and the three local MySQL transaction boundaries.
- `docs/architecture/STATE-MACHINES.md` defines Order, Payment, Refund, and InventoryReservation states, including `UNKNOWN` and close-versus-payment race policy.
- `docs/architecture/API-CONTRACT.md` distinguishes the current catalog/order baseline from later cancel, payment, refund, notification, and operations targets.

## 2. Runnable minimum engineering baseline

Status: complete and verified.

- Java 21, Spring Boot 3.5.16, MySQL 8.4, Redis 7.4, RabbitMQ 4.1, Maven, and Flyway versions/commands are recorded.
- Docker Compose starts only the required dependencies. Historical destructive bootstrap scripts are no longer mounted.
- Flyway migrations `V1` through `V4` cover the legacy schema, legacy order/Outbox additions, event catalog, and event orders.
- Existing non-empty databases baseline at version 2 only after the documented schema prerequisite; Flyway clean is disabled.
- Test seed data is isolated under `src/test/resources`.
- `InfrastructureIT` uses Testcontainers and checks Flyway, real MySQL event ordering and concurrency, Redis, RabbitMQ, and the retained voucher path.

Acceptance commands executed with the IDEA project JDK:

```text
mvn test
mvn -Pinfrastructure verify
```

Results: 33 default tests passed with no failures or skips; two isolated infrastructure tests passed with no failures or skips. Flyway applied `V1` through `V4` to a real MySQL 8.4 empty database. Flyway 11.7.2 emitted a database-version certification warning, recorded as a known limitation; migration and assertions passed.

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

The packaged application was also started against the persistent Compose stack with the IDEA project SDK, Microsoft OpenJDK 21.0.7. Flyway confirmed schema version `4`; ports 8081 and 8082 listened successfully, the public event endpoint returned HTTP 200, and the health endpoint returned `UP`.

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

## Remaining acceptance boundary

Sections 0 through 4 have passed their current automated and persistent-stack checks. Section 5 remains incomplete: cancel, timeout closing, inventory release, and restart recovery have not been claimed.
