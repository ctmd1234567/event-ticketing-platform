#!/usr/bin/env bash
set -Eeuo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8081}"
MANAGEMENT_URL="${MANAGEMENT_URL:-http://127.0.0.1:8082}"
DEMO_ADMIN_PHONE="${DEMO_ADMIN_PHONE:-13900007001}"
DEMO_BUYER_PHONE="${DEMO_BUYER_PHONE:-13900007002}"
DEMO_CANCEL_PHONE="${DEMO_CANCEL_PHONE:-13900007003}"
DEMO_TIMEOUT_PHONE="${DEMO_TIMEOUT_PHONE:-13900007004}"
POLL_TIMEOUT_SECONDS="${POLL_TIMEOUT_SECONDS:-45}"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-0.5}"
CURL_BIN="${CURL_BIN:-}"
JQ_BIN="${JQ_BIN:-}"

if [[ -z "$CURL_BIN" ]]; then
  CURL_BIN="$(command -v curl.exe || command -v curl || true)"
fi
if [[ -z "$JQ_BIN" ]]; then
  JQ_BIN="$(command -v jq || command -v jq.exe || true)"
fi
[[ -n "$CURL_BIN" ]] || { echo "missing required command: curl (or set CURL_BIN)" >&2; exit 2; }
[[ -n "$JQ_BIN" ]] || { echo "missing required command: jq (or set JQ_BIN)" >&2; exit 2; }
[[ "$POLL_TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] && (( POLL_TIMEOUT_SECONDS > 0 )) \
  || { echo "POLL_TIMEOUT_SECONDS must be a positive integer" >&2; exit 2; }

curl_cmd() { "$CURL_BIN" "$@"; }
jq_cmd() {
  local -a args=()
  local arg
  for arg in "$@"; do
    [[ "$JQ_BIN" != *.exe || "$arg" != /mnt/* ]] || arg="$(wslpath -w "$arg")"
    args+=("$arg")
  done
  "$JQ_BIN" "${args[@]}" | tr -d '\r'
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$repo_root/target"
work_dir="$(mktemp -d "$repo_root/target/v1-demo.XXXXXX")"
trap 'rm -rf -- "$work_dir"' EXIT
response_file="$work_dir/response.json"
response_output="$response_file"
[[ "$CURL_BIN" != *.exe ]] || response_output="$(wslpath -w "$response_file")"
http_status=""
run_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"

request() {
  local method="$1" path="$2" token="${3:-}" body="${4:-}" key="${5:-}"
  local -a args=(--silent --show-error --max-time 15 --request "$method"
    --output "$response_output" --write-out '%{http_code}' --header 'Accept: application/json')
  [[ -z "$token" ]] || args+=(--header "Authorization: Bearer $token")
  [[ -z "$key" ]] || args+=(--header "Idempotency-Key: $key")
  [[ -z "$body" ]] || args+=(--header 'Content-Type: application/json' --data "$body")
  http_status="$(curl_cmd "${args[@]}" "${BASE_URL}${path}")"
}

require_success() {
  local label="$1"
  if [[ ! "$http_status" =~ ^2[0-9][0-9]$ ]] || ! jq_cmd -e '.success == true' "$response_file" >/dev/null; then
    echo "$label failed: HTTP $http_status" >&2
    jq_cmd -c . "$response_file" >&2 2>/dev/null || true
    exit 1
  fi
}

login() {
  local phone="$1" code token
  request POST "/user/code?phone=${phone}"
  require_success "request local code for $phone"
  code="$(jq_cmd -er '.data.developmentCode' "$response_file")"
  request POST /user/login "" "$(jq_cmd -nc --arg phone "$phone" --arg code "$code" '{phone:$phone,code:$code}')"
  require_success "login for $phone"
  token="$(jq_cmd -er '.data | select(type == "string" and length == 32)' "$response_file")"
  printf '%s' "$token"
}

poll_order() {
  local token="$1" order_id="$2" deadline=$((SECONDS + POLL_TIMEOUT_SECONDS))
  while (( SECONDS <= deadline )); do
    request GET "/api/v1/orders/${order_id}" "$token"
    require_success "poll order $order_id"
    if jq_cmd -e '.data.status == "CLOSED"' "$response_file" >/dev/null; then
      return 0
    fi
    sleep "$POLL_INTERVAL_SECONDS"
  done
  echo "order $order_id did not close within ${POLL_TIMEOUT_SECONDS}s" >&2
  jq_cmd -c . "$response_file" >&2
  exit 1
}

echo "[1/9] Checking application health"
health_status="$(curl_cmd --silent --show-error --max-time 5 --output "$response_output" \
  --write-out '%{http_code}' "${MANAGEMENT_URL}/actuator/health")"
if [[ "$health_status" != 200 ]] || ! jq_cmd -e '.status == "UP"' "$response_file" >/dev/null; then
  echo "application health failed: HTTP $health_status at $MANAGEMENT_URL" >&2
  jq_cmd -c . "$response_file" >&2 2>/dev/null || true
  exit 1
fi

echo "[2/9] Authenticating one administrator and three buyers"
admin_token="${DEMO_ADMIN_TOKEN:-}"
[[ -n "$admin_token" ]] || admin_token="$(login "$DEMO_ADMIN_PHONE")"
buyer_token="$(login "$DEMO_BUYER_PHONE")"
cancel_token="$(login "$DEMO_CANCEL_PHONE")"
timeout_token="$(login "$DEMO_TIMEOUT_PHONE")"

starts_at="$(date -u -d '+2 hours' +%Y-%m-%dT%H:%M:%SZ)"
ends_at="$(date -u -d '+4 hours' +%Y-%m-%dT%H:%M:%SZ)"
sales_start_at="$(date -u -d '-1 minute' +%Y-%m-%dT%H:%M:%SZ)"
sales_end_at="$(date -u -d '+1 hour' +%Y-%m-%dT%H:%M:%SZ)"

echo "[3/9] Creating and publishing an isolated Event"
request POST /api/v1/admin/events "$admin_token" \
  "$(jq_cmd -nc --arg title "V1 demo $run_id" '{title:$title,description:"V1 lifecycle demo",venue:"local-demo"}')"
if [[ "$http_status" == 403 ]]; then
  echo "administrator is not allowlisted; configure ADMIN_USER_IDS or pass an allowlisted DEMO_ADMIN_TOKEN" >&2
  exit 1
fi
require_success "create demo event"
event_id="$(jq_cmd -er '.data | numbers' "$response_file")"

request POST "/api/v1/admin/events/${event_id}/sessions" "$admin_token" \
  "$(jq_cmd -nc --arg starts "$starts_at" --arg ends "$ends_at" --arg salesStart "$sales_start_at" --arg salesEnd "$sales_end_at" \
    '{name:"V1 demo session",startsAt:$starts,endsAt:$ends,salesStartAt:$salesStart,salesEndAt:$salesEnd}')"
require_success "create demo session"
session_id="$(jq_cmd -er '.data | numbers' "$response_file")"

declare -a tier_ids=()
for name in paid cancel timeout; do
  request POST "/api/v1/admin/sessions/${session_id}/ticket-tiers" "$admin_token" \
    "$(jq_cmd -nc --arg name "$name" '{name:$name,unitPrice:8800,currency:"CNY",capacity:1}')"
  require_success "create $name tier"
  tier_ids+=("$(jq_cmd -er '.data | numbers' "$response_file")")
done
tier_id="${tier_ids[0]}"

request POST "/api/v1/admin/events/${event_id}/publish" "$admin_token"
require_success "publish demo event"

echo "[4/9] Querying the published Event and inventory"
request GET "/api/v1/events/${event_id}"
require_success "query published event"
request GET "/api/v1/sessions/${session_id}/ticket-tiers"
require_success "query published ticket tier"
jq_cmd -e --argjson tier "$tier_id" \
  '.data[] | select(.id == $tier and .capacity == 1 and .available == 1 and .reserved == 0 and .allocated == 0)' \
  "$response_file" >/dev/null || { echo "initial inventory mismatch" >&2; jq_cmd -c . "$response_file" >&2; exit 1; }

echo "[5/9] Creating, replaying, and querying a pending order"
order_key="v1-demo-order-${run_id}"
order_body="$(jq_cmd -nc --argjson tier "$tier_id" '{ticketTierId:$tier,quantity:1}')"
request POST /api/v1/orders "$buyer_token" "$order_body" "$order_key"
require_success "create order"
order_id="$(jq_cmd -er '.data.id' "$response_file")"
request POST /api/v1/orders "$buyer_token" "$order_body" "$order_key"
require_success "replay order"
[[ "$(jq_cmd -er '.data.id' "$response_file")" == "$order_id" ]] || { echo "order replay created another order" >&2; exit 1; }
request GET "/api/v1/orders/${order_id}" "$buyer_token"
require_success "query pending order"
jq_cmd -e '.data.status == "PENDING_PAYMENT"' "$response_file" >/dev/null \
  || { echo "order is not pending payment" >&2; jq_cmd -c . "$response_file" >&2; exit 1; }

request GET "/api/v1/sessions/${session_id}/ticket-tiers"
require_success "query reserved inventory"
jq_cmd -e --argjson tier "$tier_id" \
  '.data[] | select(.id == $tier and .capacity == 1 and .available == 0 and .reserved == 1 and .allocated == 0)' \
  "$response_file" >/dev/null || { echo "reserved inventory mismatch" >&2; jq_cmd -c . "$response_file" >&2; exit 1; }

echo "[6/9] Paying normally and replaying the payment request"
payment_key="v1-demo-payment-${run_id}"
request POST "/api/v1/orders/${order_id}/payments" "$buyer_token" "" "$payment_key"
require_success "pay order"
payment_id="$(jq_cmd -er '.data.id' "$response_file")"
jq_cmd -e '.data.status == "SUCCEEDED"' "$response_file" >/dev/null \
  || { echo "payment did not succeed" >&2; jq_cmd -c . "$response_file" >&2; exit 1; }
request POST "/api/v1/orders/${order_id}/payments" "$buyer_token" "" "$payment_key"
require_success "replay payment"
[[ "$(jq_cmd -er '.data.id' "$response_file")" == "$payment_id" ]] || { echo "payment replay created another payment" >&2; exit 1; }

echo "[7/9] Canceling a separate order and checking ownership"
cancel_body="$(jq_cmd -nc --argjson tier "${tier_ids[1]}" '{ticketTierId:$tier,quantity:1}')"
request POST /api/v1/orders "$cancel_token" "$cancel_body" "v1-demo-cancel-${run_id}"
require_success "create cancel candidate"
cancel_order_id="$(jq_cmd -er '.data.id' "$response_file")"
request GET "/api/v1/orders/${cancel_order_id}" "$timeout_token"
[[ "$http_status" == 404 ]] || { echo "ownership check expected HTTP 404, got $http_status" >&2; exit 1; }
request POST "/api/v1/orders/${cancel_order_id}/cancel" "$cancel_token"
require_success "cancel order"
jq_cmd -e '.data.status == "CLOSED" and .data.closeReason == "USER_CANCELED"' "$response_file" >/dev/null \
  || { echo "cancel state mismatch" >&2; jq_cmd -c . "$response_file" >&2; exit 1; }

echo "[8/9] Waiting for an unpaid order to expire"
timeout_body="$(jq_cmd -nc --argjson tier "${tier_ids[2]}" '{ticketTierId:$tier,quantity:1}')"
request POST /api/v1/orders "$timeout_token" "$timeout_body" "v1-demo-timeout-${run_id}"
require_success "create timeout candidate"
timeout_order_id="$(jq_cmd -er '.data.id' "$response_file")"
payment_deadline="$(jq_cmd -er '.data.paymentDeadline' "$response_file")"
remaining_seconds=$(( $(date -u -d "$payment_deadline" +%s) - $(date -u +%s) ))
if (( remaining_seconds >= POLL_TIMEOUT_SECONDS )); then
  echo "order payment deadline exceeds demo poll budget; start the app with EVENT_ORDER_PAYMENT_WINDOW_SECONDS=30" >&2
  exit 1
fi
poll_order "$timeout_token" "$timeout_order_id"
jq_cmd -e '.data.closeReason == "PAYMENT_EXPIRED"' "$response_file" >/dev/null \
  || { echo "expiry close reason mismatch" >&2; jq_cmd -c . "$response_file" >&2; exit 1; }

echo "[9/9] Querying final orders, payment, and all three inventories"
request GET "/api/v1/orders/${order_id}" "$buyer_token"
require_success "query final order"
order_json="$(jq_cmd -c '.data | {id,status,totalAmount,currency}' "$response_file")"
jq_cmd -e '.data.status == "PAID"' "$response_file" >/dev/null \
  || { echo "order is not paid" >&2; jq_cmd -c . "$response_file" >&2; exit 1; }

request GET "/api/v1/payments/${payment_id}" "$buyer_token"
require_success "query final payment"
payment_json="$(jq_cmd -c '.data | {id,status,amount,currency,recoveryStatus}' "$response_file")"
jq_cmd -e '.data.status == "SUCCEEDED"' "$response_file" >/dev/null \
  || { echo "persisted payment did not succeed" >&2; jq_cmd -c . "$response_file" >&2; exit 1; }

request GET "/api/v1/sessions/${session_id}/ticket-tiers"
require_success "query final inventory"
inventory_json="$(jq_cmd -cer --argjson tier "$tier_id" \
  '.data[] | select(.id == $tier) | {capacity,available,reserved,allocated}' "$response_file")"
jq_cmd -e --argjson tier "$tier_id" \
  '.data[] | select(.id == $tier and .capacity == 1 and .available == 0 and .reserved == 0 and .allocated == 1)' \
  "$response_file" >/dev/null || { echo "final inventory mismatch: $inventory_json" >&2; exit 1; }

request GET "/api/v1/orders/${cancel_order_id}" "$cancel_token"
require_success "query final canceled order"
cancel_order_json="$(jq_cmd -c '.data | {id,status,closeReason}' "$response_file")"
jq_cmd -e '.data.status == "CLOSED" and .data.closeReason == "USER_CANCELED"' "$response_file" >/dev/null \
  || { echo "final cancel state mismatch" >&2; exit 1; }
request GET "/api/v1/orders/${timeout_order_id}" "$timeout_token"
require_success "query final expired order"
timeout_order_json="$(jq_cmd -c '.data | {id,status,closeReason}' "$response_file")"
jq_cmd -e '.data.status == "CLOSED" and .data.closeReason == "PAYMENT_EXPIRED"' "$response_file" >/dev/null \
  || { echo "final expiry state mismatch" >&2; exit 1; }
request GET "/api/v1/sessions/${session_id}/ticket-tiers"
require_success "query final inventory across tiers"
all_inventory_json="$(jq_cmd -cer --argjson paid "${tier_ids[0]}" --argjson cancel "${tier_ids[1]}" --argjson timeout "${tier_ids[2]}" \
  '[.data[] | select(.id == $paid or .id == $cancel or .id == $timeout)]
  | {tiers:length,capacity:(map(.capacity)|add),available:(map(.available)|add),reserved:(map(.reserved)|add),allocated:(map(.allocated)|add)}' "$response_file")"
jq_cmd -e '.tiers == 3 and .capacity == 3 and .available == 2 and .reserved == 0 and .allocated == 1' \
  <<<"$all_inventory_json" >/dev/null || { echo "final inventory mismatch: $all_inventory_json" >&2; exit 1; }

echo "V1 HTTP demo passed"
echo "event=$event_id session=$session_id paidOrder=$order_id canceledOrder=$cancel_order_id expiredOrder=$timeout_order_id payment=$payment_id"
echo "paidOrder=$order_json"
echo "canceledOrder=$cancel_order_json"
echo "expiredOrder=$timeout_order_json"
echo "payment=$payment_json"
echo "finalInventory=$all_inventory_json"
