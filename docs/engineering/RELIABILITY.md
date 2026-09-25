# Reliability and recovery

The application creates orders synchronously in MySQL. RabbitMQ carries notification side effects only. The same local transaction that confirms payment or closes an order writes a stable `ORDER_PAID:<orderId>` or `ORDER_CLOSED:<orderId>` Outbox intent. A rollback removes both business change and intent. The consumer never creates an order or changes inventory.

## Outbox delivery

`EventNotificationPublisher` claims pending events, or events whose `PROCESSING` lease expired, and retries with bounded backoff. It checks a correlated Publisher Confirm and mandatory Return against the declared durable exchange, binding, and queue. A Confirm is evidence of broker acceptance, **not** proof that `et_notification` was written. Eight failed publish attempts lead to `MANUAL_REQUIRED`. A crash after broker acceptance but before the local `PUBLISHED` update can publish the same `event_id` again. The notification table's unique `event_id` makes repeated delivery one business effect; consumer ACK follows the database commit.

Consumer technical failures retry at most four times with 1, 2, and 4 second delays before isolation. Unprocessable payloads are recorded with their original bytes and reason, then dead lettered without repeated parsing. A dead letter queue is an investigation aid, not a zero loss guarantee: MySQL failure, dead letter routing mistakes, broker or volume loss, and operator deletion remain possible. The Compose broker is one persistent node, not high availability.

An ADMIN can redrive a `MANUAL_REQUIRED` event or a `PUBLISHED` event lacking its notification, retaining the original event ID and recording actor, reason, action, and outcome in `et_notification_redrive`. Redrive uses a lease to serialize operators; an uncertain outcome requires checking the Outbox, failure record, broker queue, and final notification before retry. Repeated redrive remains safe through consumer idempotency.

## Payment, closure, and refund

A transport timeout is `UNKNOWN`, not payment failure. A persisted business number is reused for callback, gateway query, recovery scan, and authorized same number retry. Payment confirmation and order closure serialize on the order: payment first moves `reserved` to `allocated`; closure first moves `reserved` to `available`. A later confirmed charge on a closed order creates one full compensation refund and cannot reopen the order. Refund timeout uses the same refund number for query and retry. Allocated inventory is not returned to sale after an ordinary full refund.

Targeted reconciliation reads a bounded `REPEATABLE READ` snapshot for stale reservations, payment/refund contradictions, long `UNKNOWN`, and missing or delayed notification effects. Its thresholds are triage signals. Only the precise `CLOSED + SUCCEEDED + RELEASED + no refund` case has an automatic, conditional repair that inserts the one late-payment refund intent. Other contradictions require manual review. Independent pages are separate snapshots; a complete cross-page audit needs stopped writes or another pass.

## Evidence and limits

Isolated MySQL and RabbitMQ integration tests covered rollback, duplicate publication, lease expiry, Return, Confirm timeout, poison messages, redrive, and consumer commit/ACK windows. In an isolated real broker `stop_app/start_app` exercise, 100 persisted intents eventually produced 100 distinct notifications within a 120 second test budget. Publisher and consumer crash windows used deterministic fault injection and repeated delivery; they did not kill the JVM or inspect ACK frames. A later local process restart exercise confirmed expired-order closure, payment `UNKNOWN` recovery, and expired Outbox lease recovery; see [restart evidence](results/event-process-restart-20260925.txt). Redis outage returned 503 during unavailable authentication and a later request succeeded after recovery; [the audit](results/event-redis-recovery-audit-20260925.txt) retained inventory conservation. MySQL failure paths are covered by transaction rollback and isolated integration tests; this is not a full database outage or disaster recovery exercise.

See [Testing](TESTING.md) for suite boundaries and [Performance](PERFORMANCE.md) for bounded operational observations.

## Isolated dependency observations (2026-09-24)

In a disposable fixture, Redis interruption made an authenticated Event read return 503 and recovery restored 200. MySQL interruption made an authenticated read and an order attempt return 503; retrying the same order idempotency key after recovery committed exactly one order. Broker interruption did not undo an already committed order closure: after restart, its Outbox intent became `PUBLISHED` and one notification was persisted. On app restart, a due order closed, its reservation released, and inventory stayed conserved. These were single local fault exercises; they do not establish failover or durability under infrastructure loss.

In a separate 20-order fixture, all 20 authenticated closures ended with exactly one `ORDER_CLOSED` notification and a published intent. Database polling observed completion p50/p95 of 0.614/0.642 seconds; this is an upper-bound observation with polling overhead. [Fixture](results/isolated-notification-fixture-20260924.json) and [completion data](results/isolated-notification-completion-20260924.json) retain individual evidence. Queue-ready and dead-letter-ready gauges are cached every five seconds; broker interruption changed samples from zero to `NaN` and back to zero, so missing data is not reported as an empty queue. [Gauge fault data](results/isolated-queue-metrics-fault-20260924.json).

The request path uses server-side Redis identity, bounded verification-code use, admin authorization, raw-body HMAC and timestamp validation for simulated callbacks, and durable audit for recovery actions. An authenticated user cannot select another actor for order, payment, or refund mutations.
