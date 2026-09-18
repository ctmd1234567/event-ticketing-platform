# Refactoring Stage A Engineering Asset Register

Date completed: 2026-09-18

Scope: REFACTORING-CHECKLIST.md stage A only.

## Snapshot and boundaries

- Repository: /mnt/d/learnys/event-trading-platform
- Branch and HEAD: main at ee751827980ef9c5be796becbcf95cc40216edd2.
- Local origin/main: ee751827980ef9c5be796becbcf95cc40216edd2.
- The tracked worktree and index were clean when stage A began.
- DEVELOPMENT-CHECKLIST.md is private through .git/info/exclude; section 6 is checked and section 7 is not checked.
- The applicable rule file is /mnt/d/learnys/AGENTS.md; no nearer AGENTS.md exists.
- Stage A did not edit Java, runtime configuration, tests, migrations, or business data, and did not run tests, load scripts, or benchmarks.

These classifications preserve evidence and identify later decision points. They do not authorize stage B, section 7, V2, deletion, or automatic integration into the Event path.

## Git provenance and protection

### Early com.hmdp snapshot

- cd293f343395f277a743d05a8fdeba74f5779885 contains the earlier com.hmdp Voucher, Shop, Blog, identity, cache, Redis-lock, RabbitMQ, SQL, and test assets.
- Its readable chain contains 239bd7842d2e539de993086d34b4d925842242d7, ffb30bf68ac5ec024d432bf93b92fa9962a97528, and cd293f343395f277a743d05a8fdeba74f5779885.
- Before stage A, no branch or tag pointed to the chain and git fsck --no-reflogs --unreachable reported it as unreachable.
- Stage A created local-only ref refs/archive/engineering-assets/hmdp-cd293f3 at cd293f343395f277a743d05a8fdeba74f5779885. It protects all three readable ancestors from ordinary unreachable-object pruning.
- Verification: the ref resolves to the expected object, git rev-list --count returns 3, and those commits are no longer reported as unreachable.
- No remote ref was created or pushed. Preserve this local ref when cloning, replacing, or cleaning the object database; it is not an off-machine backup.
- Classification: historical archive. Exit condition: none during this refactor; a later deliberate archive may add an external bundle but must not silently discard this chain.

### Relationship to current main

- 4613e0b6fd1132cf9bde5b400b63ff0cdaadbd53 is the root of current main and already contains the com.eventplatform application, MySQL transaction source of truth, durable order requests, Outbox, listener, security, tests, and documentation.
- Git proves 4613e0b is an ancestor of 5c880d3, 5479ae8, and ee75182. cd293f3 is not an ancestor of 4613e0b or ee75182.
- Both roots have visibly related Voucher, Shop, and Blog names and structures, but this repository has no parent, merge, tag, import note, or other metadata proving the exact external upstream or transformation.
- Conclusion: current-project version history begins at 4613e0b. The com.hmdp chain is a related predecessor snapshot, not a verified complete upstream history. Stronger source claims are unconfirmed.

### Milestone evidence

- 4613e0b6fd1132cf9bde5b400b63ff0cdaadbd53: current-history root and initial engineering baseline.
- 5c880d3aec893f59e177fb441351c6055efe455a: short reservation path, transaction-external idempotency read, multi-key Lua limits, batched publication, and k6 inputs.
- 5479ae8e7e8bc33a776b85b3038aeb29fe6dcce1: stock buckets, fair semaphore, batch listener/fulfillment, Outbox leases/metrics, session Lua, and experiment changes.
- ee751827980ef9c5be796becbcf95cc40216edd2: current Event baseline through checklist section 6 and its simulated payment boundary.

## Asset register

### Event domain, lifecycle, and payment

- Source: Event ordering starts at 50c547b59a9bf890c4f2671987b5c1e812236072, lifecycle at 5bbf8c876d395964858776690c0321de8bf6ed2e, and payment runs through ee75182.
- Current assets: Event catalog/order packages, payment package, Event controllers, Flyway V3-V6, architecture documents, and CHECKLIST-0-6.md.
- Evidence: catalog/order unit tests, EventOrderLifecycleIT, payment contract/integration tests, controller/security tests, and verification records.
- Classification: formal retention and current product path.
- Decision/exit: synchronous Event order creation and reservation stay in one MySQL local transaction. Old consumer-created Voucher orders must not be connected. Replacement requires separately accepted behavior and equivalent evidence.

### Identity and security

- Source: AuthCodes, RequestLimits, and TokenFilter exist at 4613e0b; multi-key rate-limits.lua at 5c880d3; RequestLimits, TokenFilter, and auth-session.lua change at 5479ae8.
- Current assets: those classes/scripts, SecurityConfig, and login/logout.
- Evidence: AuthCodesTest, RequestLimitsTest, SecurityRegressionTest, the logout assertion in VoucherOrderControllerTest, and Redis coverage in InfrastructureIT.
- Classification: session handling, atomic code consumption, logout, and identity safety are formal retention. Voucher key construction is temporary legacy wiring; the multi-key algorithm is retained pending an explicit Event decision.
- Exit: stage B must relocate mixed tests without losing assertions. Voucher-specific wiring exits only after its caller is isolated and the algorithm has an accepted Event use or independent evidence home.

### Stock buckets

- Source: added by 5479ae8.
- Current assets: StockBuckets.java, performance-upgrade.sql, VoucherServiceImpl initialization, and Voucher reservation wiring.
- Evidence: OrderTransactionsTest, InfrastructureIT, seed-order-capacity-bucketed.sql, and the k6 harness.
- Classification: independent engineering experiment temporarily wired to legacy Voucher.
- Decision/exit: do not replace Event available/reserved/allocated without Event lock evidence and a separately accepted invariant design. After stages B/C provide an isolated reproduction path and product dependencies are gone, the adapter may leave product packages while history/evidence remains.

### Fair admission and old metrics

- Source: OrderPerformance and OutboxMetrics were added at 5479ae8.
- Current assets: fair process-local semaphore, bounded wait/429, permit release, timers, and old Outbox backlog/age metrics.
- Evidence: OrderTransactionsTest passes through OrderPerformance. There is no focused timeout/interruption test or raw metric export.
- Classification: independent experiment/possible migration candidate; metrics are temporary legacy instrumentation.
- Decision/exit: a process-local semaphore is not a global connection limit, and old names are not Event metrics. Retain until isolated acquisition, rejection, interruption, and release evidence exists; migrate only if Event overload evidence justifies it.

### Legacy transactions and asynchronous fulfillment

- Source: OrderTransactions begins at 4613e0b, changes at 5c880d3, and gains buckets/batching/metrics at 5479ae8.
- Current assets: OrderTransactions, VoucherOrderController/Service, and SeckillVoucherListener reserve stock/request/Outbox, then create the old final order in a consumer transaction.
- Evidence: OrderTransactionsTest covers idempotency, batches, rollback, legacy lookup, sale rejection, oversell, and same-user races; InfrastructureIT retains the real Voucher round trip.
- Classification: temporary legacy implementation with retained engineering evidence.
- Decision/exit: preserve short-transaction, duplicate-key, rollback, lock, and batch lessons but never connect consumer-created orders to Event. Stage B separates tests and stage C supplies an experiment profile or fixed-history route before stage D can consider removing adapters.

### Outbox and RabbitMQ

- Source: OutboxPublisher, QueueConfig, and listener exist at 4613e0b; batching at 5c880d3; leases, batch fulfillment, Confirm/Return work, and metrics change at 5479ae8.
- Evidence: OutboxPublisherTest covers outage retention, confirm without premature completion, and send-before-wait batching; InfrastructureIT covers a real broker/listener effect.
- Classification: pending V2 design migration plus temporary legacy runtime wiring.
- Decision/exit: retain lease/retry, Return, Confirm, redelivery, batching, and commit-before-ACK experience. V2 must define Event payload/effects and must not copy consumer-created orders or the old completed meaning. Old components exit only after separated tests and equivalent Event or independent experiment evidence.

### Load scripts, seeds, and claims

- Source: k6 and initial seeds were added at 5c880d3; bucket/token changes at 5479ae8.
- Tracked inputs: order-capacity.js plus capacity SQL/Lua seeds and cleanup.
- Protected untracked Event inputs: event-trading-baseline.sh, audit-event-trading-baseline.sql, seed-event-trading-baseline.sql, and seed-event-trading-baseline-tokens.lua.
- Classification: Voucher materials are independent historical experiments; Event materials are protected section-7 work in progress and were not run or modified.
- Saved raw inputs: commits, scripts, seeds, and embedded workload parameters.
- Historical summaries: README lists short local 1000-1600 target-RPS results and calls 1500 the highest passing recorded level under stated criteria.
- Missing raw outputs: no k6 JSON/CSV/console export, system metric capture, or database snapshot is stored. README numbers are historical summaries, not independently revalidated raw results.
- Unverified: Voucher numbers do not prove Event ordering, payment/refund throughput, exact capacity, production SLA, or long-duration stability.
- Exit: stage C chooses an isolated reproduction route or fixed version before removing the old HTTP adapter. Section 7 must create versioned Event evidence rather than reuse old numbers.

### Documentation

- Assets: bilingual README, requirements/security documents, architecture documents, verification records, and private checklists.
- Classification: accepted Event architecture/evidence is formal retention. Stale product text and legacy performance sections are historical documentation pending stage E, not runtime truth.
- Exit: stage E reconciles claims with final code while preserving dates, versions, limitations, and evidence links.

## Flyway V1-V6 integrity snapshot

These are SHA-256 content checksums, not Flyway schema-history signed CRC values.

- V1__legacy_base.sql: e6a4328b9a02cee3f26be848a7bc41f6d796b7f175122a323cfbc8b7376b1805
- V2__legacy_order_outbox.sql: 23c4b90e3594ab4faba7f343c656937908be15c3012816f72ac9313d130b6750
- V3__event_catalog.sql: b94812aa71dc4fe1c06c852d49286a2df84663b40cafdc85fafba46a3d714ddb
- V4__event_orders.sql: eb93fc9e98b882784442629bbfcec697774c49846a5dfc6b7ab6c889f434deab
- V5__event_order_expiry_retry.sql: ffe5647c4ccd0bd98eebd119104e5ee8c16bffa72c53a1d2af80a1575f07f522
- V6__event_payments.sql: 40f69a24fc19f06caebc687083a45d0a094b6b0d73cdd21cebfcba3ea1a32fd8

All six matched HEAD during stage A. Flyway and development databases were not executed or queried.

## Protected pre-existing untracked work

- REFACTORING-CHECKLIST.md
- scripts/demo-v1.sh
- loadtest/audit-event-trading-baseline.sql
- loadtest/event-trading-baseline.sh
- loadtest/seed-event-trading-baseline-tokens.lua
- loadtest/seed-event-trading-baseline.sql

Stage A intentionally updates the untracked checklist. The other five files remained byte-for-byte unchanged. This register is the only new worktree file created by stage A.

## Verification and gaps

- Verified: branch/HEAD/local origin equality, initial clean tracked state, untracked inventory, commit metadata/ancestry, milestone file changes, current callers/tests, archive ref, migration hashes, and unchanged migrations.
- Not run by design: Java/Maven/Failsafe, Compose, database Flyway, load scripts, k6, or business-data inspection.
- Gaps: exact upstream/import relationship is unprovable; historical 415/1500-RPS raw outputs are absent; Event demo/baseline inputs have no section-7 acceptance; semaphore and some metric paths lack independent focused tests.

## Stage B entry

Stage B requires separate authorization. First split InfrastructureIT and infrastructure-seed.sql into Event acceptance and legacy message experiments, then relocate mixed identity/configuration assertions without deletion. OrderTransactionsTest, OutboxPublisherTest, RequestLimitsTest, the logout assertion, and the 1000-request Event invariant need explicit destinations before product-code pruning.
