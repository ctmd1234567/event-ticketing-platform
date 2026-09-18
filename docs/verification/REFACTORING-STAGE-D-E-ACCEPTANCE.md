# Refactoring Stages D/E Acceptance Record

Date: 2026-09-18

Branch: `main`

Candidate base: `7b97c0f` (`refactor: isolate legacy order experiment`)

Scope: stages D and E, plus the stage-F entry gate. Private checklist item 7 was not started.

## Outcome

The default product surface now presents one Event Trading story: identity and
security, event catalog, synchronous order/inventory, payment and compensation,
and the recovery jobs needed by those boundaries. The obsolete Shop, Blog,
Follow, Upload, social-profile and Voucher CRUD wrappers were removed. The
legacy concurrency and messaging work remains available only through the
explicit `legacy-experiment` engineering profile or Git history.

No database table or migration was removed. No protected untracked asset was
edited, deleted, executed or added to the index.

## Stage D: removed product surface

Removed groups:

- Shop, ShopType, Blog, Follow and Upload controllers plus their services,
  implementations, mappers, entities and dedicated compatibility/cache tests;
- Voucher and VoucherOrder CRUD wrappers, mapper XML and unused domain types;
- social-profile and sign-in statistics endpoints while retaining verification
  code, login, logout and current-user identity;
- `UserInfo`, `ScrollResult`, `CacheClient`, `RedisData`,
  `ImageStorage` and their now-obsolete tests;
- upload-only configuration and exception handling, obsolete security matchers,
  unused Redis/system constants, and the no-longer-used pagination extension.

The `legacy-experiment` HTTP adapter now calls the retained
`OrderTransactions` experiment directly. This preserves the historical
short-transaction, idempotency, rollback, rate-limit, Outbox and messaging
evidence without keeping a second public Voucher application layer.

## Retained assets and reasons

- MyBatis remains the mapper foundation for identity, Event, order and payment.
- Hutool remains in active token, authentication and verification-code paths.
- Redis remains part of identity sessions and verification/rate-limit behavior.
- AMQP remains a compile/runtime dependency of the optional
  `legacy-experiment` profile and its isolated integration test.
- `StockBuckets`, `RequestLimits`, `OrderPerformance`,
  `OrderTransactions`, legacy Outbox/messaging code and metrics remain
  engineering assets with their exits governed by the stage-A asset register.
- Flyway `V1` through `V6` remain immutable; deleting Java wrappers does not
  authorize rewriting or dropping historical tables.

Historical implementation remains recoverable from normal Git history. The
earlier hmdp object family is additionally protected by local reference
`refs/archive/engineering-assets/hmdp-cd293f3`.

## Stage E: documentation convergence

Updated both READMEs around the Event business loop, failure-boundary highlights,
architecture, proof, quick start, API map and roadmap. Historical Voucher
throughput is collapsed into a clearly labeled engineering-experiment note and
is not presented as Event or production performance.

Also aligned:

- `docs/PROJECT-REQUIREMENTS.md` with the actual V1/V2/V3 boundaries;
- `docs/SECURITY-FIXES.md` with implemented payment/security and Flyway behavior;
- `docs/architecture/API-CONTRACT.md` with the real response envelope, routes,
  status behavior and idempotency rules;
- `docs/architecture/PAYMENT-BOUNDARY-DESIGN.md` and
  `docs/verification/CHECKLIST-0-6.md` with commit and revalidation status.

The obsolete merchant Postman definition and shop-type request were removed.
The complete Event request collection remains private-checklist item 7 and was
not fabricated during cleanup.

## Verification evidence

Executed through IntelliJ IDEA 2026.1.3 with Microsoft OpenJDK 21.0.7 against
the final code shape before the documentation-only closeout:

- full project rebuild: success, zero reported compilation problems;
- 65 retained default test methods: all passed;
- `EventInfrastructureIT`: 3 passed;
- `EventOrderLifecycleIT`: 2 passed;
- `PaymentBoundaryIT`: 26 passed;
- `LegacyMessagingIT`: 1 passed.

Total: 97 methods, zero failures and zero ignored. Of these, 96 cover the
default/Event product boundary and one covers the isolated legacy experiment.
The integration classes used isolated Testcontainers dependencies. No Maven aggregate run, capacity-boundary search or production-SLA validation was
performed during the D/E acceptance. A later user-authorized 1500-RPS
reverification is recorded separately in
[CONCURRENCY-EXPERIMENT-1500-RPS-2026-09-18.md](CONCURRENCY-EXPERIMENT-1500-RPS-2026-09-18.md). Flyway 11.7.2 emitted its existing MySQL 8.4 certification warning,
and MySQL emitted the existing UNSIGNED decimal/floating deprecation warning;
migrations and assertions passed.

## Integrity checks

Flyway migration SHA-256 values after cleanup:

```text
e6a4328b9a02cee3f26be848a7bc41f6d796b7f175122a323cfbc8b7376b1805  V1__legacy_base.sql
23c4b90e3594ab4faba7f343c656937908be15c3012816f72ac9313d130b6750  V2__legacy_order_outbox.sql
b94812aa71dc4fe1c06c852d49286a2df84663b40cafdc85fafba46a3d714ddb  V3__event_catalog.sql
eb93fc9e98b882784442629bbfcec697774c49846a5dfc6b7ab6c889f434deab  V4__event_orders.sql
ffe5647c4ccd0bd98eebd119104e5ee8c16bffa72c53a1d2af80a1575f07f522  V5__event_order_expiry_retry.sql
40f69a24fc19f06caebc687083a45d0a094b6b0d73cdd21cebfcba3ea1a32fd8  V6__event_payments.sql
```

Protected untracked asset SHA-256 values:

```text
a7943bca652b4e75d4e14301f848a79887841a6bc3aff28673034ee9f4c53cfd  scripts/demo-v1.sh
bb87b0c2766af64fbc35a893bb731c612bc300ca1970153188c987089261512f  loadtest/audit-event-trading-baseline.sql
70182d6b476f07950133eaf2df78ae0901f04748c17cbdc1c1dd861b384b8972  loadtest/event-trading-baseline.sh
d34fa8180901f164ef44b2ef94b640b341c4b9b768eeb35d76c54866707e39ac  loadtest/seed-event-trading-baseline-tokens.lua
ce4534396fe3fe835426928998810e35c5299af261b96abbb0afe8c547e4e827  loadtest/seed-event-trading-baseline.sql
```

These values are checked again at handoff. The protected files remain untracked,
which is intentional.

## Stage F entry gate

The entry conditions are satisfied: the default product boundary is focused,
items 0-6 and migrations remain intact, engineering results have traceable
homes, and the README exposes the product story without borrowing legacy
performance. Stage F is only a gate here. The Event demo, request collection,
joint acceptance and real-write baseline belong to item 7 and require separate
authorization.
