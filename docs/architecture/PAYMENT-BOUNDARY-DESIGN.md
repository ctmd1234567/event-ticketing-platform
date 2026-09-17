# Checklist 6.1: Payment Boundary Design

Date: 2026-09-16

Baseline reviewed: `5bbf8c8` (checklist 0–5).

Status: sections 6.1–6.5 prepared in source. On 2026-09-17, the scoped 6.4
IDEA runs passed: 10 H2 payment, 10 MySQL payment and 8 order regression tests;
the final 6.5 extended payment suites each completed 14 tests without failures.
Section 6.1 froze this design. Section 6.2 adds the V6 storage contract and
section 6.3 adds the independent simulated-gateway boundary. Section 6.4 adds
local orchestration/result transactions. Section 6.5 adds receipt-based callbacks
and query recovery. Refund compensation, HTTP endpoints and full item-6 runtime
acceptance remain later slices.
Where the broader state-machine/API documents describe future capabilities,
the narrower decisions here govern this increment. Update those documents when
the corresponding implementation is delivered.

## Scope and invariants

- One simulated provider, CNY integer fen, one ticket per order. Zero-price
  orders remain supported because the existing catalog permits them.
- One logical payment per order in V1. Repeated attempts reuse its payment
  number; a different idempotency key cannot create another charge for the order.
- Payment success is immutable. Refunds are separate records.
- Closing an order does not prove the provider canceled or failed its payment.
  Existing uncertain payments must still be queried after local closure.
- A normal successful confirmation atomically updates payment, order,
  reservation, inventory, and transition history.
- A successful payment discovered after closure atomically records payment
  success and one full compensation refund intent. The order stays `CLOSED`.
- Compensation never updates inventory or reservation state. The released ticket
  may already belong to another buyer.
- `available + reserved + allocated = capacity`; each counter is nonnegative.
- No user-requested refunds, notifications, MQ changes, generalized
  reconciliation framework, benchmark, or section-7 demo script in this increment.

## Storage contract for the future V6 migration

Use InnoDB, BIGINT identifiers and integer-fen amounts, UTC timestamps, explicit
foreign keys within each boundary, and database CHECK constraints for states,
nonnegative amounts, and CNY. Opaque numbers, keys, signatures, and hashes use
case-sensitive ASCII comparison. Do not edit V1–V5.

### et_payment

- Identity: `id`, `payment_number`, `order_id`, `user_id`, `provider`.
- Immutable request: `idempotency_key`, `request_hash`, `amount`, `currency`.
- Result: `status`, nullable `provider_transaction_id`, nullable `succeeded_at`.
- Recovery: `recovery_status`, `attempts`, `next_attempt_at`, `lease_token`,
  `lease_until`, `last_error`, `version`, `created_at`, `updated_at`.
- Unique: `payment_number`, `order_id`, `(user_id, idempotency_key)`, and
  `(provider, provider_transaction_id)` when the provider transaction is known.
- Foreign key: `order_id -> et_order.id`. Read amount/currency/user from the
  locked order snapshot; never accept them from the payment-create caller.
- Same user/key/payload returns the existing payment. Same key/different order
  conflicts. A different key on the same order returns a conflict with no new
  payment or gateway call. Recovery uses the existing payment ID/number.

### et_refund

- Identity: `id`, `refund_number`, `payment_id`, `reason` (`LATE_PAYMENT`).
- Immutable request: `amount`, `currency`; these equal the successful payment.
- Result: `status`, nullable `provider_refund_id`, nullable `succeeded_at`.
- Recovery: the same recovery fields as `et_payment`.
- Unique: `refund_number`, `payment_id`, and non-null `provider_refund_id` for
  the single simulated provider. `payment_id` references `et_payment.id`.
- Ownership/order association is derived through payment, avoiding another
  independently mutable order reference. V1 permits only one full refund per
  payment, even if callers use different retry or callback IDs.

### et_payment_callback

- Fields: `id`, `provider`, `event_id`, `kind` (payment/refund), `business_number`,
  `payload_hash`, validated payload fields, `status`, `reason`, timestamps.
- Unique: `(provider, event_id)`. The first committed receipt binds the event ID
  to its payload hash. Retain subsequent conflicting hashes in history without
  overwriting or applying the original receipt.
- Status: `RECEIVED`, `APPLIED`, `DUPLICATE`, `REJECTED`, `UNMATCHED`.
- Authenticate before storing a trusted receipt. Invalid signatures receive a
  rejection and do not create an unbounded unauthenticated receipt archive.
- A valid signature with an unknown business number is retained as `UNMATCHED`,
  visible to administrators; it cannot update an order. Different hashes for an
  existing event ID are conflicts, never additional business effects.

### et_payment_history

- Fields: `id`, nullable `payment_id`, nullable `refund_id`, `action`, `from_status`,
  `to_status`, `source`, nullable `callback_id`, nullable `actor_id`, nullable
  `operation_key`, nullable `request_hash`, bounded `detail`, `created_at`.
- Accepted transitions append history in their business transaction. Rejected
  callbacks retain their rejection in the receipt instead of rolling it back
  with an exception. Acknowledgment follows commit.
- Administrative recovery records actor, target, reason, before/after state and
  operation key. Unique `(actor_id, operation_key)` protects operator retries;
  a changed target/payload for the same key conflicts.
- This is a payment-specific audit trail, not a generic audit subsystem.
  An unmatched callback may have no payment ID; its callback ID and bounded
  diagnostic detail retain the association and any conflicting payload hash.

### sim_gateway_payment and sim_gateway_refund

- Payment fields: `payment_number`, opaque `order_reference`, `amount`, `currency`,
  `status`, `provider_transaction_id`, timestamps.
- Refund fields: `refund_number`, `payment_number`, `amount`, `currency`, `status`,
  `provider_refund_id`, timestamps.
- Business numbers are primary/unique keys; provider IDs are unique. Refund has
  a unique `payment_number` and references the gateway payment, enforcing one
  full reversal per successful charge in V1.
- No foreign keys to local order/payment/refund tables and no access to their
  state. Validate refund amount against the gateway's successful payment.
- Both support `PROCESSING`, `SUCCEEDED`, `FAILED`. Terminal success is immutable;
  repeated requests return the same effect. A retryable explicit rejection may
  resume under the same number after the simulated rejection condition is removed.
  The immutable request binding cannot change on retry.

## Transaction and lock boundaries

The simulated provider uses dedicated tables in the same physical MySQL for
simple deployment, but commits through a separate transaction (`REQUIRES_NEW`
on an independently invoked bean or explicit transaction template). This is a
simulation of independent outcomes, not a separate database failure domain.

The orchestration method has no surrounding business transaction. Reject ambient
transactions at its public boundary (`NEVER` or an equivalent explicit guard).
Do not call the gateway while holding local order/payment/inventory locks.

1. Local preparation transaction: lock order, verify ownership and eligibility,
   persist the business number and payment intent, commit.
2. Local short claim transaction: claim a recoverable record and persist attempt
   metadata, commit. Recheck order eligibility before issuing a new charge.
3. Gateway transaction: create/query by the same business number, commit its
   result. A test transport wrapper may throw only after this commit to model
   response loss.
4. Local result transaction: lock the order and apply the trusted result. If the
   application crashes between 3 and 4, durable recovery resumes from the local
   intent and queries the independent gateway record.

All local mutation paths take locks in this order, skipping unused rows:
`order -> payment -> refund -> reservation -> ticket tier`.
Close keeps its existing `order -> reservation -> ticket tier` order. Resolve
immutable associations with an unlocked lookup, then re-read under locks; never
lock payment/refund first and subsequently request the order lock. Claims that
only lock a recovery row must commit before entering result application.

The existing create-order transaction starts with the tier and inserts a new
order/reservation. Payment and close only operate on committed orders; they must
not acquire catalog locks or introduce a dependency on an uncommitted new order.

Normal success requires `PENDING_PAYMENT` and a matching `RESERVED` reservation;
guard all three updates and roll back the whole result transaction on an
unexpected affected-row count or inventory inconsistency. If the locked order
is already `CLOSED`, create the refund intent instead, without inventory writes.

The winner is the first committed local order transition, not provider charge
time or callback arrival time. Do not initiate a fresh payment after the stored
deadline. An already initiated payment may still confirm while the order is
pending; expiration does not itself close the order until its transaction wins.
If close wins during an in-flight gateway request, its eventual charge must be
compensated. An expiry scan finding `PAID` returns no change; an explicit user
cancel of `PAID` still returns 409.

## Outcome states and bounded recovery

- Payment: `CREATED -> PROCESSING -> SUCCEEDED | FAILED | UNKNOWN`.
- Refund: `REQUESTED -> PROCESSING -> SUCCEEDED | FAILED | UNKNOWN`.
- `UNKNOWN`/`PROCESSING` can converge to a trusted success or failure. A same-number
  query/retry can resume processing. Only a trusted provider rejection establishes
  `FAILED`; transport timeout/reset/500 establishes uncertainty.
- Explicit `FAILED` may return to processing only via an audited same-number
  retry, with the payment order still eligible for a new charge. No automatic
  reopening of a closed order and no automatic creation of a replacement payment.
- Recovery status is separate: `AUTO`, `MANUAL_REQUIRED`, `NONE`. Exhausting an
  uncertainty budget sets `MANUAL_REQUIRED` while preserving `UNKNOWN`/`PROCESSING`.
  Manual handling is not a successful payment or refund outcome.
- Every result application re-reads locked state. Late timeout or processing
  responses cannot overwrite `SUCCEEDED`. Contradictory terminal evidence is
  retained for manual inspection rather than forcing a state regression.

Proposed configurable defaults: scan every 1 second, batch 100, retry delay 5
seconds, lease 30 seconds, maximum 5 automatic attempts including the initial
request. These are design settings, not measured recovery guarantees. Index
`(recovery_status, next_attempt_at, id)`; expired leases can be reclaimed after
restart. Startup and periodic scanning use the same persistent query. A claim
increments attempts and moves next-attempt time to its lease deadline so a crash
cannot lose the work or hot-loop the same row.

Query before resubmission. Gateway NOT_FOUND is not proof of a failed charge.
Resubmit a payment only under its original number and while its order is still
eligible; otherwise keep querying until the bounded manual handoff. Refunds may
be resubmitted under their original refund number after a not-found result.
Persisted refund intents survive a crash before the first gateway refund call.

Lease tokens fence stale retry/error bookkeeping. Trusted success from an older
attempt remains eligible for idempotent application; a stale timeout does not
overwrite a newer result. Recovery claims and finalization must use conditional
updates so concurrent scanners cannot reset another worker's lease or retry budget.

## Callback and API contract

Use HMAC-SHA256 over the timestamp and exact raw request body, constant-time
signature comparison, and a configurable freshness window (default 300 seconds).
Secrets come from configuration; no committed usable default. Missing secrets
disable callback acceptance. Redelivery may use a fresh signature/timestamp with
the same event ID and payload. Test signing helpers stay in test code.

Before applying a callback validate provider, kind, business number, provider
transaction/refund ID, order reference, amount and currency against the immutable
local binding. Provider transaction uniqueness prevents cross-order attachment.
Conflicting duplicate payloads receive 409. First persist/authenticate the receipt
in a short independent transaction; handle unique-key races by reading and
comparing the winner. Then apply its result in the local business transaction,
locking the receipt after business rows and atomically marking it applied.
No receipt-holding transaction may subsequently acquire the order lock.
Persisted `RECEIVED` receipts are retried after crashes using the same bounded
recovery policy; store attempts, next-attempt time and manual status on receipts
as well. Previously applied identical receipts are acknowledged without another
inventory/refund effect. Response acknowledgment follows committed application
or durable rejection/unmatched recording.

Planned routes, following the existing response envelope:

- `POST /api/v1/orders/{orderId}/payments`: owned create, `Idempotency-Key` required.
- `GET /api/v1/payments/{paymentId}`: owned local status, with no mutation.
- `POST /api/v1/payments/{paymentId}/refresh`: owned gateway query and local recovery.
- `GET /api/v1/refunds/{refundId}`: owned compensation status.
- `POST /api/v1/refunds/{refundId}/refresh`: owned query of an existing refund.
- `POST /api/v1/payment-callbacks/simulated`: signed payment/refund events; exempt
  only this exact POST route from user bearer authentication.
- `GET /api/v1/admin/payment-recovery`: query manual payment/refund items and
  unmatched/rejected trusted callback receipts, with bounded pagination.
- `POST /api/v1/admin/payments/{paymentId}/retry` and
  `POST /api/v1/admin/refunds/{refundId}/retry`: audited, idempotent, same-number
  recovery with ADMIN authorization and a required reason.

Refresh queries may apply new trusted evidence but cannot silently restart an
exhausted command budget. Administrative retry first queries existing results;
it cannot mark success manually. Other users receive 404. No user-facing gateway
fault switches, refund-create endpoint, or global response-envelope rewrite.

## Implementation slices and acceptance gates

1. 6.2: `V6__event_payments.sql` now implements the storage contract. The existing
   real-MySQL infrastructure test now expects V1 through V6. On 2026-09-17,
   `EventPaymentServiceIT` verified all six migrations on a clean MySQL 8.4
   database; `InfrastructureIT` itself was not rerun in this verification.
2. 6.3: `JdbcSimulatedPaymentGateway` now provides the simulated-gateway
   interface/implementation and post-commit response-loss wrapper. Its focused
   unit tests verify that payment and refund results survive a lost response and
   caller rollback; execution remains pending IDEA verification.
3. 6.4: `EventPaymentService` now provides local payment orchestration/result
   transactions; `EventOrderService` treats a paid expiry candidate as a no-op.
   See [the scoped verification record](../verification/CHECKLIST-6-4.md).
4. 6.5: `PaymentCallbackService` now authenticates signed simulated payment
   callbacks, persists idempotent receipts, and applies them atomically with
   payment state. `PaymentRecoveryScanner` claims and queries durable
   `PROCESSING`/`UNKNOWN` payment work and retries received receipts after a
   business-transaction crash. See [the scoped verification record](../verification/CHECKLIST-6-5.md).
5. 6.6: add unique compensation intent, refund execution/recovery and manual retry.
6. 6.7: add controllers/security rules and ownership/signature/idempotency tests.
7. 6.8: real-MySQL `PaymentBoundaryIT`, using bounded latches and fault injection.
   Force each winner by pausing after its real order lock is acquired, starting
   the competing path, and releasing the winner to commit. Include cancellation
   and expiry, stale expiry selection, concurrent callbacks, local rollback after
   gateway success, recovery after restart, refund response loss, exhausted/manual
   recovery, and a new buyer reserving the released ticket before late payment.
   Assert all order/reservation/payment/refund states, exact inventory counters,
   unique provider effects, and successful replay. Never use sleep to select a winner.
8. 6.9: final static review and user-run IDEA tests with Microsoft OpenJDK 21;
   extend `CHECKLIST-0-5.md` into `CHECKLIST-0-6.md` and update both READMEs only
   with actual evidence. Preserve historical results as historical.

Sections 6.1–6.4 require static review and their listed IDEA tests before
acceptance. Their implementation does not mark
checklist 6 accepted. The remaining slices require separate implementation
authorization; commit and push still require explicit user approval.
