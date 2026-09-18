# Event Trading Platform Requirements

Updated: 2026-09-18

This document separates the implemented product, the next main-project increments, and optional evidence. Technology names are not acceptance criteria by themselves; behavior and claims require code plus reproducible verification.

## Product objective

Build a focused modular-monolith backend for event catalog, ticket inventory, ordering, payment uncertainty, and compensation. MySQL is the transactional source of truth. Redis supports identity and bounded traffic control. RabbitMQ is introduced into the Event product only when a real non-core notification use case and its failure evidence exist.

## V1 — core trading

### Implemented and verified: checklist items 0–6

- Java 21 / Spring Boot modular monolith with Flyway V1–V6.
- ADMIN event, session, and ticket-tier authoring; public catalog reads.
- One-ticket synchronous ordering with server-side CNY price snapshots.
- MySQL-local order, reservation, and inventory transaction with `available + reserved + allocated = capacity`.
- Request idempotency, one-order-per-user-and-tier purchase limit, ownership isolation, and no overselling.
- User cancellation, timeout closure, persistent retry/backoff, and restart recovery.
- Simulated payment gateway with independent commit, `UNKNOWN / PROCESSING`, signed callbacks, query recovery, bounded manual handoff, and late-charge compensation.
- Deterministic unit and real-MySQL concurrency/race verification.

### Remaining V1 delivery work: checklist item 7

- A coherent Event demo path and request collection.
- A newly measured Event real-write baseline with isolated data and explicit limits.
- Final delivery notes that distinguish current product behavior from historical engineering experiments.

Item 7 has not been implemented or claimed by the cleanup stages.

## V2 — main-project completion target

Planned checklist items 8–12:

- Event notification intent stored transactionally with committed business changes.
- RabbitMQ publication with lease/retry, Confirm/Return handling, consumer idempotency, post-commit ACK, bounded retry, DLQ metadata, and safe redrive.
- User-initiated full refunds reusing the existing payment/refund uncertainty model; no partial refunds and no inventory return after allocation.
- Targeted reconciliation for stuck reservations, contradictory payment/refund state, long-running uncertainty, and failed notification work.
- Evidence for critical SQL, dependency failure/recovery, Normal/Stress/Spike load, backlog age, connection pools, JVM, and error/rejection behavior.
- Accurate API/migration documentation and basic CI proving that required tests actually run.

V2 does not require microservices, Kafka, Elasticsearch, Nacos, Sentinel, Kubernetes, Seata, sharding, or a second cache system.

## V3 — optional evidence

Choose only when useful for presentation or risk reduction:

- 60-minute or longer soak with predeclared thresholds.
- Alerting or tracing driven by an observed diagnostic need.
- Backup/restore or deployment rollback exercise.
- Hosted demo or additional visualization.

V3 is not an internship-application or project-completion gate.

## Cross-cutting rules

- Core order, closure, and normal payment confirmation remain short MySQL local transactions.
- Network timeout, reset, or 5xx is uncertainty—not proof of failure.
- The first committed local order transition decides payment-versus-close races.
- A confirmed late charge creates exactly one compensation refund and never reallocates inventory.
- Authentication identity comes from the server; users cannot access another user's order, payment, or refund.
- Secrets stay outside source control. Administrative recovery is authenticated, idempotent, reasoned, and auditable.
- New migrations are forward-only. Flyway V1–V6 are immutable history.
- Every performance result states workload, duration, environment, business outcomes, failures/rejections, and missing evidence.
- Historical Voucher tests and load results protect engineering knowledge but do not define the Event product or its performance.
