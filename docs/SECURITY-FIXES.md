# Security and Consistency Record

Updated: 2026-09-25

## Current controls

- `TokenFilter` accepts explicit bearer tokens, loads the server-side Redis session, and always clears thread-local identity in `finally`.
- Only verification-code/login, the exact simulated callback POST, health/Prometheus, and public Event catalog reads are anonymous.
- `/api/v1/admin/**` requires an ADMIN identity configured by server-side user IDs.
- Orders, payments, and refunds derive the actor from the authenticated context; another user's resource is hidden.
- Verification codes are configurable, rate-limited, single-use, and never pretend to send when SMS delivery is disabled.
- Logout revokes the Redis token and clears the current thread identity.
- Callback acceptance requires a configured HMAC secret, timestamp freshness, exact raw-body verification, immutable amount/currency/business binding, and provider-event idempotency.
- ADMIN payment/refund recovery requires authentication, an idempotency key, a bounded reason, and durable audit history.

## Consistency controls

- MySQL is authoritative for Event orders and inventory. Redis is not an inventory source of truth.
- Order creation, reservation creation, and inventory movement share one local transaction.
- Closure and release share one local transaction; payment confirmation and allocation share another.
- Guarded updates, unique constraints, fixed lock order, and idempotent reads prevent overselling and duplicate business effects.
- Gateway calls commit outside local order transactions. Lost responses become `UNKNOWN`; recovery queries the same business number.
- A late successful charge after closure creates one full compensation refund and never changes inventory again.
- Recovery work is persistent, leased, bounded, restart-safe, and can become `MANUAL_REQUIRED` instead of being falsely reported as complete.

## Removed attack surface

The default product excludes the former Shop, Blog, Follow, upload/image, sign-in, merchant Voucher administration, and logical-expiry shop-cache HTTP surfaces. Historical implementation remains recoverable from Git; it is not part of the current product claim.

The Voucher-order adapter, stock buckets, and historical Rabbit topology are no longer included in the current application. Their implementation and test material remain available at commit `103d1da`; the [archive note](history/LEGACY-VOUCHER-ARCHIVE.md) explains reproduction and data retention.

## Database and runtime boundary

Flyway validates the unchanged V1–V9 migrations and applies V10 to rename six Voucher tables without deleting rows. `clean` is disabled. Existing databases may use the documented version-2 baseline only after the prerequisite schema is confirmed.

Default Compose starts MySQL, Redis, and RabbitMQ. The AMQP dependency supports Event notification delivery; core Event orders remain synchronous MySQL transactions.

## Verification

- Default tests do not connect to a personal database.
- Infrastructure tests use isolated Testcontainers MySQL and RabbitMQ.
- Manual data-writing preparation tests remain excluded by the shared `manual` group setting.
- The V1 verification record covers the default suite and the Event Testcontainers integration suite; CI runs both Maven entry points on Java 21.

Publisher confirms and consumer acknowledgements do not create cross-system exactly-once delivery. Event notification uses durable Outbox intent, at-least-once delivery, idempotent effects, bounded retries, and audited manual redrive; see the [notification recovery record](verification/CHECKLIST-9-MQ-RECOVERY.md).
