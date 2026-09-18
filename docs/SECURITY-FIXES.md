# Security and Consistency Record

Updated: 2026-09-18

Historical baseline: `cd293f3`. Current payment baseline: `ee75182`. Cleanup profiles and test boundaries are committed through `7b97c0f`; the current D/E candidate removes the remaining shop/social/upload surface.

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

The current cleanup removes Shop, ShopType, Blog, Follow, UserInfo, upload/image handling, sign-in streaks, merchant Voucher administration, logical-expiry shop caching, and their HTTP/security matchers. Their historical fixes remain recoverable from Git and the engineering asset register; they are no longer current product claims.

The optional `legacy-experiment` profile retains only the minimum authenticated Voucher-order adapter plus JDBC transaction, stock-bucket, Outbox, Rabbit topology/listener, metrics, and tests required to reproduce the historical engineering experiment. It is disabled by default.

## Database and runtime boundary

Flyway automatically validates and applies immutable migrations V1–V6. `clean` is disabled. Existing databases may use the documented version-2 baseline only after the legacy prerequisite schema is confirmed. No cleanup stage rewrites migrations, clears Redis, manipulates queues, or recreates persistent volumes.

Default Compose starts MySQL and Redis. RabbitMQ and its health contribution are enabled only for `legacy-experiment`. AMQP dependencies remain because that experiment is still compiled and verified.

## Verification

- Default tests do not connect to a personal database.
- Infrastructure tests use isolated Testcontainers MySQL/Redis/RabbitMQ.
- Manual data-writing preparation tests remain excluded by the shared `manual` group setting.
- The 2026-09-18 cleanup candidate passed an IDEA rebuild, 65 default tests, 31 Event integration tests, and one isolated legacy experiment test with no failures or ignored tests.

Publisher confirms and consumer acknowledgements do not create cross-system exactly-once delivery. Future Event messaging must combine durable intent, at-least-once delivery, idempotent effects, bounded retries, and observable manual recovery.
