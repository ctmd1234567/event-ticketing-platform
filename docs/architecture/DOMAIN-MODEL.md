# Domain Model

## Purpose

This document defines the target business model for the event trading platform. It is the source of truth for module ownership, aggregate boundaries, terminology, and business invariants. Database migrations and application code must follow this model unless an Architecture Decision Record explicitly changes it.

## Frozen V1 Boundary

- One order contains one ticket tier and exactly one ticket. The only currency is `CNY`, and money uses integer fen.
- Capacity cannot change after publication. Inventory is `available + reserved + allocated = capacity`, and every counter is nonnegative.
- A separate `InventoryReservation` record is the reservation fact; order status does not duplicate reservation state.
- The V1 purchase limit is one order per user and ticket tier. A retry with the same idempotency key returns the original result; a different key is rejected, including after cancellation.
- V1 has no user-initiated refund. A late confirmed charge after local closure creates one full compensating refund and never changes inventory again. V2 may add user-initiated full refunds, which also do not return allocated inventory to sale. Partial refunds are out of scope.
- Core ordering is synchronous. RabbitMQ is retained for non-core notification work and is not required to commit an order.

## V1 Local Transaction Boundaries

1. Place order: lock and validate the ticket tier, move one unit from `available` to `reserved`, create the order, and create its `RESERVED` reservation in one MySQL transaction.
2. Close order: conditionally move a `PENDING_PAYMENT` order to `CLOSED`, move its reservation from `RESERVED` to `RELEASED`, and return one unit from `reserved` to `available` in one MySQL transaction.
3. Confirm normal payment: persist the trusted payment result, conditionally move the order to `PAID`, move its reservation from `RESERVED` to `CONFIRMED`, and move one unit from `reserved` to `allocated` in one MySQL transaction.

When an Outbox event is introduced for one of these changes, its publication intent is inserted in that same transaction. Gateway charge/refund execution remains outside these local database transactions; a transport timeout is `UNKNOWN`, never proof of failure.

## Architecture Style

The application remains a modular monolith. Modules communicate through application services and domain events while sharing one MySQL database. Redis is an acceleration and traffic-control layer, not a transactional source of truth. RabbitMQ provides asynchronous delivery, and the Transactional Outbox guarantees that business changes and outgoing events are committed together.

Each business module uses the following internal structure when complexity requires it:

```text
module/
├─ api/             HTTP controllers and request/response models
├─ application/     use cases and transaction boundaries
├─ domain/          aggregates, value objects, policies, and repository ports
└─ infrastructure/  MyBatis repositories, external clients, and messaging adapters
```

Do not create empty layers or interfaces only to satisfy this layout.

## Bounded Contexts

| Context | Owns | Responsibilities |
|---|---|---|
| Identity | User, Role, AccessToken | Authentication, authorization, token revocation |
| Event Catalog | Event, EventSession, TicketTier | Event authoring, publication, sales windows, public catalog |
| Inventory | Inventory, InventoryReservation | Atomic reservation, confirmation, release, reconciliation |
| Ordering | Order, OrderItem | Idempotent order creation, pricing snapshot, order lifecycle |
| Payment | Payment, Refund | Payment attempts, signed callbacks, cancellation, refunds |
| Messaging | OutboxEvent, InboxMessage | Durable event publication and consumer deduplication |
| Notification | NotificationEvent, SubscriptionCursor | SSE delivery, reconnect, replay, duplicate suppression |
| Operations | AuditLog, ReconciliationRun | Failure inspection, retry, redrive, reconciliation, operator audit |

## Core Aggregates

### Event

An event is the administrative root for one sellable event.

Key fields: `id`, `title`, `description`, `venue`, `status`, `createdBy`, `createdAt`, `updatedAt`.

States: `DRAFT`, `PUBLISHED`, `OFF_SALE`, `ENDED`.

Rules:

- Only draft events may change essential descriptive fields freely.
- An event can be published only when it has at least one valid session and sellable ticket tier.
- Taking an event off sale prevents new reservations but does not invalidate existing orders.

### EventSession

An event session represents one scheduled occurrence.

Key fields: `id`, `eventId`, `name`, `startsAt`, `endsAt`, `salesStartAt`, `salesEndAt`, `status`.

States: `DRAFT`, `ON_SALE`, `OFF_SALE`, `ENDED`.

Rules:

- `startsAt` must be earlier than `endsAt`.
- `salesStartAt` must be earlier than `salesEndAt` and no later than `startsAt`.
- A session cannot be put on sale unless its parent event is published.

### TicketTier

A ticket tier is a sellable product within one event session.

Key fields: `id`, `sessionId`, `name`, `unitPrice`, `currency`, `purchaseLimitPerUser`, `status`.

States: `DRAFT`, `ON_SALE`, `OFF_SALE`, `SOLD_OUT`.

Rules:

- Money is stored as an integer in the smallest currency unit.
- Price and currency are copied into the order item at reservation time.
- A price change never changes an existing order.

### Inventory

Inventory is the MySQL source of truth for one ticket tier.

Key fields: `ticketTierId`, `capacity`, `available`, `reserved`, `allocated`, `version`.

Invariant:

```text
0 <= available
0 <= reserved
0 <= allocated
available + reserved + allocated = capacity
```

Redis may cache availability or reject obvious overload, but a Redis result must never create or restore authoritative inventory.

### InventoryReservation

One reservation binds inventory to one order item.

Key fields: `id`, `orderId`, `orderItemId`, `ticketTierId`, `quantity`, `status`, `expiresAt`.

States: `RESERVED`, `CONFIRMED`, `RELEASED`.

Rules:

- There is at most one reservation per order item.
- Successful payment moves inventory from `reserved` to `allocated` exactly once.
- Cancellation or timeout moves inventory from `reserved` back to `available` exactly once.
- Neither a normal full refund nor a late-payment compensating refund changes allocated or already released inventory.

### Order

An order is the consistency boundary for the customer purchase lifecycle.

Key fields: `id`, `orderNumber`, `userId`, `eventId`, `sessionId`, `status`, `totalAmount`, `currency`, `idempotencyKey`, `expiresAt`, `paidAt`, `canceledAt`, `refundedAt`, `version`, `createdAt`, `updatedAt`.

Rules:

- `(userId, idempotencyKey)` is unique.
- An order has exactly one item with quantity `1` in V1.
- `(userId, ticketTierId)` is unique in V1; cancellation does not restore purchase eligibility.
- A repeated create request with the same key and payload returns the original order.
- Reusing a key with a different payload is rejected.
- Order prices are immutable snapshots.
- State changes use conditional updates or row locking so competing operations have one winner.

### Payment

A payment records one payment attempt for an order.

Key fields: `id`, `paymentNumber`, `orderId`, `provider`, `providerTransactionId`, `amount`, `currency`, `status`, `callbackPayloadHash`, `createdAt`, `updatedAt`, `succeededAt`.

Rules:

- `paymentNumber` is globally unique.
- `(provider, providerTransactionId)` is unique when a provider transaction exists.
- Amount and currency must match the order snapshot.
- Duplicate callbacks return the committed result without repeating effects.

### Refund

A refund records one full reversal of a successful payment. V1 creates refunds only for late-payment compensation; V2 may expose a user-initiated full-refund command.

Key fields: `id`, `refundNumber`, `orderId`, `paymentId`, `amount`, `reason`, `status`, `idempotencyKey`, `providerRefundId`, `requestedBy`, `createdAt`, `updatedAt`, `succeededAt`.

Rules:

- The refund amount equals the successful payment amount; partial refunds are unsupported.
- `(paymentId, idempotencyKey)` is unique.
- Refund success never changes inventory in the fixed V1/V2 policy.

## Supporting Records

### OutboxEvent

Required fields: `id`, `aggregateType`, `aggregateId`, `eventType`, `eventVersion`, `payload`, `traceId`, `status`, `attemptCount`, `nextAttemptAt`, `leasedUntil`, `lastError`, `createdAt`, `publishedAt`.

### InboxMessage

Required fields: `consumerName`, `messageId`, `eventType`, `payloadHash`, `status`, `receivedAt`, `processedAt`, `lastError`.

`(consumerName, messageId)` is unique and is the consumer idempotency boundary.

### AuditLog

Every sensitive administrative action records the actor, action, target, request trace, before/after summary, result, IP address, and timestamp. Audit records are append-only.

## Initial Domain Events

- `EventPublished`
- `EventTakenOffSale`
- `InventoryReserved`
- `InventoryReservationConfirmed`
- `InventoryReservationReleased`
- `OrderCreated`
- `OrderPaid`
- `OrderCanceled`
- `OrderExpired`
- `PaymentSucceeded`
- `PaymentFailed`
- `RefundRequested`
- `RefundSucceeded`
- `RefundFailed`
- `OrderRefunded`

Event names describe completed facts. Payloads carry identifiers and immutable facts, not database entities.

## Cross-Module Rules

1. MySQL is authoritative for orders, payments, refunds, and inventory.
2. Every externally retried command has an idempotency boundary.
3. Every asynchronous consumer assumes at-least-once delivery.
4. Business state and its outgoing event are committed in one transaction.
5. A module does not update another module's tables outside an explicitly documented transaction use case.
6. Money comparisons include amount and currency.
7. Timestamps are persisted in UTC and rendered in the client's time zone.
8. IDs are opaque to clients; business numbers are separate from primary keys.

## Legacy Migration Map

| Legacy model | Target model | Removal condition |
|---|---|---|
| `Voucher` | `TicketTier` | Public catalog and order creation use ticket tiers |
| `SeckillVoucher` | `Inventory` | Reservation and release are implemented and reconciled |
| `VoucherOrder` | `Order`, `OrderItem`, `InventoryReservation` | The new state machine passes concurrency and migration tests |
| Shop review features | Removed | The event catalog provides all required demonstration data |
| Follow, feed, and sign-in features | Removed | No target API, test, or documentation depends on them |

Legacy tables and code are removed only after the replacement vertical slice is operational. They must not receive new features.
