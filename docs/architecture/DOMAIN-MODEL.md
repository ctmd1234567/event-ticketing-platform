# Domain Model

## Purpose

This document describes the implemented Event Ticketing Platform business model and its transaction invariants. The current code and migrations govern executable details.

## Business boundary

- One order contains one ticket tier and exactly one ticket. The only currency is `CNY`, and money uses integer fen.
- Capacity cannot change after publication. Inventory is `available + reserved + allocated = capacity`, and every counter is nonnegative.
- A separate `InventoryReservation` record is the reservation fact; order status does not duplicate reservation state.
- The purchase limit is one order per user and ticket tier. A retry with the same idempotency key returns the original result; a different key is rejected, including after cancellation.
- A late confirmed charge after local closure creates one full compensating refund. A paid user can request one full refund. Neither path returns allocated inventory to sale. Partial refunds are unsupported.
- Core ordering is synchronous. RabbitMQ is retained for non-core notification work and is not required to commit an order.

## Local transaction boundaries

1. Place order: lock and validate the ticket tier, move one unit from `available` to `reserved`, create the order, and create its `RESERVED` reservation in one MySQL transaction.
2. Close order: conditionally move a `PENDING_PAYMENT` order to `CLOSED`, move its reservation from `RESERVED` to `RELEASED`, and return one unit from `reserved` to `available` in one MySQL transaction.
3. Confirm normal payment: persist the trusted payment result, conditionally move the order to `PAID`, move its reservation from `RESERVED` to `CONFIRMED`, and move one unit from `reserved` to `allocated` in one MySQL transaction.

Payment confirmation and order closure insert their notification Outbox intent in the same transaction. Gateway charge/refund execution remains outside these local database transactions; a transport timeout is `UNKNOWN`, never proof of failure.

## Architecture Style

The application remains a modular monolith. Modules communicate through application services while sharing one MySQL database. Redis is an acceleration and traffic-control layer, not a transactional source of truth. RabbitMQ provides asynchronous delivery, and the Transactional Outbox guarantees that business changes and outgoing events are committed together.

## Module ownership

| Area | Main records | Responsibility |
|---|---|---|
| Identity | User and Redis session | Authentication and authorization |
| Catalog | Event, EventSession, TicketTier | Publication and sales windows |
| Inventory and order | Inventory, reservation, order | Synchronous reservation, close and allocation |
| Payment | Payment, Refund, callback history | Gateway result and recovery |
| Notification | Outbox event, notification, failure/redrive record | Durable intent and in-app effect |
| Operations | Payment history and reconciliation result | Audited recovery and targeted review |

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
- An order has exactly one item with quantity `1`.
- `(userId, ticketTierId)` is unique; cancellation does not restore purchase eligibility.
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

A refund records one full reversal of a successful payment, requested by the user or created to compensate a late charge.

Key fields: `id`, `refundNumber`, `orderId`, `paymentId`, `amount`, `reason`, `status`, `idempotencyKey`, `providerRefundId`, `requestedBy`, `createdAt`, `updatedAt`, `succeededAt`.

Rules:

- The refund amount equals the successful payment amount; partial refunds are unsupported.
- `(paymentId, idempotencyKey)` is unique.
- Refund success never changes inventory in the current policy.

## Supporting records

`et_outbox_event` persists a stable event ID, type, order association, payload, publish status, attempts, next attempt and lease. `et_notification.event_id` is unique, so redelivery cannot create a second in-app notification. `et_notification_failure` retains poison or exhausted delivery details; `et_notification_redrive` records operator decisions. Payment history and callback receipts retain their respective audit facts. The implementation does not use a generic Inbox table or generic audit subsystem.

The emitted notification types are `ORDER_PAID` and `ORDER_CLOSED`. Other domain changes remain synchronous MySQL state transitions.

## Cross-module rules

1. MySQL is authoritative for orders, payments, refunds, and inventory.
2. Retried order and payment commands retain stable identity and request binding.
3. Notification delivery assumes duplicates; the consumer commits its effect before ACK.
4. Relevant business state and notification intent commit in one transaction.
5. Money comparisons include amount and currency.
6. Gateway charge and refund transactions are independent of the local order result transaction.
7. A trusted success after closure creates one compensation refund; it never revives the order.
