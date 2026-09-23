# API Contract

Updated: 2026-09-23

This document describes the implemented V1 HTTP surface and the added V2 item 8 notification read endpoint. User-created refunds, Event Outbox operations, DLQ/redrive, and general reconciliation are planned work and are not current endpoints.

## Conventions

- Base path for product APIs: `/api/v1`
- Media type: `application/json`
- Authentication: `Authorization: Bearer <token>`
- Command idempotency header where listed: `Idempotency-Key`
- Timestamps: ISO-8601 UTC
- Money: integer fen and ISO currency; V1 uses CNY

## Actual response envelope

Success:

```json
{
  "success": true,
  "data": {},
  "errorMsg": null,
  "total": null
}
```

Failure:

```json
{
  "success": false,
  "data": null,
  "errorMsg": "Diagnostic message",
  "total": null
}
```

Null fields may be omitted by Jackson configuration. The current API does not expose a stable machine error-code field or response `traceId`; clients must not assume the planned envelope from older design drafts.

## Authentication and identity

- `POST /user/code` — request a verification code subject to rate limits and configured delivery mode.
- `POST /user/login` — consume a code and create a Redis-backed bearer session.
- `POST /user/logout` — revoke the presented token.
- `GET /user/me` — return the authenticated identity.

Only the code and login routes are anonymous. The `local` profile may disclose a development code and must remain local-only.

## Public catalog

- `GET /api/v1/events`
- `GET /api/v1/events/{eventId}`
- `GET /api/v1/sessions/{sessionId}/ticket-tiers`

Availability is informational and does not reserve stock.

## ADMIN catalog commands

All routes below require ADMIN:

- `POST /api/v1/admin/events`
- `POST /api/v1/admin/events/{eventId}/sessions`
- `POST /api/v1/admin/sessions/{sessionId}/ticket-tiers`
- `POST /api/v1/admin/events/{eventId}/publish`
- `POST /api/v1/admin/events/{eventId}/take-off-sale`

Create-order price and currency always come from the ticket tier. V1 accepts one ticket and CNY.

## Owned order API

- `POST /api/v1/orders` — requires `Idempotency-Key`; creates one order and reservation in one local transaction.
- `GET /api/v1/orders/{orderId}` — owned order only.
- `GET /api/v1/orders` — current user's orders.
- `POST /api/v1/orders/{orderId}/cancel` — closes an unpaid owned order and releases its reservation.

For order creation, the same user/key/payload returns the original result. Reusing the key with changed payload conflicts. A different key for the same user/tier is rejected by the separate purchase limit, including after cancellation. Cancel does not require an idempotency header; repeated calls observe the terminal state and do not release inventory twice.

Create request:

```json
{
  "ticketTierId": 10001,
  "quantity": 1
}
```

## Payment and compensation API

- `POST /api/v1/orders/{orderId}/payments` — requires `Idempotency-Key`.
- `GET /api/v1/payments/{paymentId}`
- `POST /api/v1/payments/{paymentId}/refresh`
- `GET /api/v1/refunds/{refundId}`
- `POST /api/v1/refunds/{refundId}/refresh`

V1 has one logical payment per order. Replays reuse the same payment number. Transport timeout/reset becomes `UNKNOWN`, not `FAILED`. Refresh queries trusted provider state and may converge the local record.

Refund routes expose only system-created late-payment compensation. There is no user-facing refund-create endpoint. If local closure commits first and an already initiated payment later succeeds, the result transaction keeps the order closed and creates exactly one full refund intent. Refund success never returns allocated or released inventory to sale.

## Owned notifications

- `GET /api/v1/notifications` — authenticated user's latest 100 in-app notifications, newest first.

The response uses the normal `Result` envelope. Each item has `id`, `eventId`, `orderId`, `type`, `content`, and `createdAt`. Payment success and order closure produce notification intents; delivery is asynchronous, so an empty response immediately after the transaction is possible. This endpoint has no pagination or read-state mutation.

## Simulated callback

`POST /api/v1/payment-callbacks/simulated` is the only anonymous callback route. It requires:

- `X-Simulated-Event-Id`
- `X-Simulated-Timestamp`
- `X-Simulated-Signature`
- the exact signed raw JSON body

The server verifies freshness, HMAC-SHA256, provider result identifiers, business number, order reference, amount, currency, and immutable request binding. Identical redelivery is idempotent; conflicting reuse of an event ID is rejected or retained for manual inspection without another business effect.

## ADMIN recovery

- `GET /api/v1/admin/payment-recovery?limit=50&afterId=0`
- `POST /api/v1/admin/payments/{paymentId}/retry`
- `POST /api/v1/admin/refunds/{refundId}/retry`

Retry mutations require `Idempotency-Key` and a nonblank reason of at most 255 characters. They query/reuse the original business number; an operator cannot manually mark a payment or refund successful.

## HTTP status behavior

The current controllers use standard Spring status exceptions for validation, authentication/authorization, missing resources, business conflicts, rate/overload rejection, and internal errors. Common statuses are 400, 401, 403, 404, 409, 429, and 500. Successful creates currently return the normal `Result.ok(...)` response rather than a guaranteed HTTP 201. No client should rely on unimplemented 202/422/503 policies from earlier target designs.

## Ownership and versioning

Users access only their own orders, payments, and refunds. Another user's resource is reported as absent. ADMIN functions use dedicated `/api/v1/admin/**` routes. Breaking HTTP changes require a new API version; additive fields may appear in the existing envelope.

## Optional legacy experiment

When the explicit `legacy-experiment` profile is active, authenticated `/voucher-order/**` endpoints exist only to reproduce a historical JDBC/Outbox/RabbitMQ engineering experiment. They are not part of the Event product contract and must not be added to the product request collection.
