# State Machines

## General Rules

- State transitions are commands, not arbitrary field updates.
- Every transition verifies the current state with a conditional update or row lock.
- Repeating a completed transition is a no-op that returns the committed result.
- An invalid transition returns `409 Conflict` with a stable business error code.
- State, state history, and the outgoing Outbox event change in one transaction.
- Consumers and callbacks may arrive more than once or out of order.

## Event

```mermaid
stateDiagram-v2
    [*] --> DRAFT
    DRAFT --> PUBLISHED: publish
    PUBLISHED --> OFF_SALE: take off sale
    OFF_SALE --> PUBLISHED: resume sale
    PUBLISHED --> ENDED: end
    OFF_SALE --> ENDED: end
```

- Publishing requires at least one valid session and ticket tier.
- Resuming requires a future sellable session.
- `ENDED` is terminal; published events cannot be deleted.

## Order

```mermaid
stateDiagram-v2
    [*] --> PENDING_PAYMENT: inventory reserved
    PENDING_PAYMENT --> PAID: valid payment succeeds
    PENDING_PAYMENT --> CLOSED: user cancel or deadline wins
    PAID --> FULFILLED: entitlement issued
    PAID --> REFUNDING: refund accepted
    FULFILLED --> REFUNDING: refund accepted
    REFUNDING --> REFUNDED: refund succeeds
    REFUNDING --> PAID: refund fails before fulfillment
    REFUNDING --> FULFILLED: refund fails after fulfillment
```

Rules:

- `PENDING_PAYMENT` owns a `RESERVED` inventory reservation.
- `PAID` and `FULFILLED` own a `CONFIRMED` reservation.
- `CLOSED` owns a `RELEASED` reservation; `closeReason` distinguishes cancellation from expiration.
- A late payment for `CLOSED` never reopens the order. It creates one compensating full refund and a visible recovery record.
- Timeout and payment processing compete on the same guarded transition; exactly one wins.
- A refund failure returns the order to the state captured when the refund started.
- `CLOSED` and `REFUNDED` are terminal.

## Payment

```mermaid
stateDiagram-v2
    [*] --> CREATED
    CREATED --> PROCESSING: provider request accepted
    CREATED --> CLOSED: order canceled
    PROCESSING --> UNKNOWN: timeout or response lost
    UNKNOWN --> PROCESSING: same payment number retry or query
    UNKNOWN --> SUCCEEDED: trusted query or callback
    UNKNOWN --> FAILED: trusted query or callback
    PROCESSING --> SUCCEEDED: signed success callback
    PROCESSING --> FAILED: signed failure callback
    PROCESSING --> CLOSED: provider close confirmed
    FAILED --> PROCESSING: new attempt
```

Rules:

- `SUCCEEDED` is immutable; refunds are separate aggregates.
- Duplicate callbacks with the same provider transaction and payload are acknowledged without repeated effects.
- Conflicting amount, currency, order, or payload is rejected and audited.
- Unknown callbacks are stored for reconciliation instead of discarded.
- Transport timeout, HTTP 500, or connection reset produces `UNKNOWN`, not `FAILED`.
- Payment success updates payment, order, reservation, history, and Outbox records atomically.

## Refund

```mermaid
stateDiagram-v2
    [*] --> REQUESTED
    REQUESTED --> PROCESSING: provider request accepted
    PROCESSING --> SUCCEEDED: signed success result
    PROCESSING --> FAILED: provider rejects or retries exhausted
    FAILED --> PROCESSING: audited retry
```

Rules:

- `SUCCEEDED` is terminal.
- Duplicate requests return the refund identified by `(paymentId, idempotencyKey)`.
- User full-refund success transitions a paid order to `REFUNDED`; late-payment compensation leaves the order `CLOSED`.
- Refund success does not change inventory: normal paid inventory remains allocated, while a closed order was already released.
- Automatic retries are bounded; exhausted failures require an audited operator action.

## Inventory Reservation

```mermaid
stateDiagram-v2
    [*] --> RESERVED
    RESERVED --> CONFIRMED: payment succeeds
    RESERVED --> RELEASED: order canceled or expires
```

- Every transition is guarded by the previous state.
- Releasing an already released reservation is a no-op.
- Inventory counters and reservation state change in one transaction.
- `CONFIRMED` is represented by allocated inventory and is never returned to sale by refund.
- Reconciliation calculates expected counters from reservations and reports discrepancies before repair.

## Race Resolution

### Payment callback versus timeout or cancellation

1. Both operations require `PENDING_PAYMENT`.
2. Both lock or conditionally update the same order.
3. The first committed transition wins.
4. If cancellation or timeout wins, later payment success triggers a compensating refund.
5. If payment wins, cancellation or timeout performs no state change.

### Duplicate or out-of-order messages

Consumers claim `(consumerName, messageId)` in the Inbox table in the same transaction as the business effect. Completed messages are acknowledged as duplicates. Valid but premature messages remain retryable or enter reconciliation; they never force an illegal transition.
