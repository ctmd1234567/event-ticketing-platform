#!/usr/bin/env bash
set -Eeuo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8081}"
MANAGEMENT_URL="${MANAGEMENT_URL:-http://127.0.0.1:8082}"
DEMO_ADMIN_PHONE="${DEMO_ADMIN_PHONE:-13900007001}"
DEMO_PAID_PHONE="${DEMO_PAID_PHONE:-13900007002}"
DEMO_CANCEL_PHONE="${DEMO_CANCEL_PHONE:-13900007003}"
DEMO_TIMEOUT_PHONE="${DEMO_TIMEOUT_PHONE:-13900007004}"
POLL_TIMEOUT_SECONDS="${POLL_TIMEOUT_SECONDS:-45}"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-0.5}"
JQ_BIN="${JQ_BIN:-}"
CURL_BIN="${CURL_BIN:-}"

if [[ -z "$CURL_BIN" ]]; then
  if [[ -x "/mnt/c/Windows/System32/curl.exe" ]]; then
    CURL_BIN="/mnt/c/Windows/System32/curl.exe"
  elif command -v curl >/dev/null 2>&1; then
    CURL_BIN="$(command -v curl)"
  else
    echo "missing required command: curl (or set CURL_BIN)" >&2
    exit 2
  fi
fi
curl() { "$CURL_BIN" "$@"; }

if [[ -z "$JQ_BIN" ]]; then
  if command -v jq >/dev/null 2>&1; then
    JQ_BIN="$(command -v jq)"
  elif [[ -x "/mnt/c/Users/ctmd12334567/AppData/Local/Microsoft/WinGet/Packages/jqlang.jq_Microsoft.Winget.Source_8wekyb3d8bbwe/jq.exe" ]]; then
    JQ_BIN="/mnt/c/Users/ctmd12334567/AppData/Local/Microsoft/WinGet/Packages/jqlang.jq_Microsoft.Winget.Source_8wekyb3d8bbwe/jq.exe"
  else
    echo "missing required command: jq (or set JQ_BIN)" >&2
    exit 2
  fi
fi
jq() {
  local -a args=()
  local arg
  for arg in "$@"; do
    [[ "$JQ_BIN" != *.exe || "$arg" != /mnt/* ]] || arg="$(wslpath -w "$arg")"
    args+=("$arg")
  done
  "$JQ_BIN" "${args[@]}" | tr -d '\r'
}

for command in date mktemp; do
  command -v "$command" >/dev/null || { echo "missing required command: $command" >&2; exit 2; }
done
[[ "$POLL_TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] && (( POLL_TIMEOUT_SECONDS > 0 )) \
  || { echo "POLL_TIMEOUT_SECONDS must be a positive integer" >&2; exit 2; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$repo_root/target"
work_dir="$(mktemp -d "$repo_root/target/item7-demo.XXXXXX")"
trap 'rm -rf -- "$work_dir"' EXIT
run_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
response_file="$work_dir/response.json"
response_output="$response_file"
[[ "$CURL_BIN" != *.exe ]] || response_output="$(wslpath -w "$response_file")"
http_status=""

request() {
  local method="$1" path="$2" token="${3:-}" body="${4:-}" key="${5:-}"
  local -a args=(--silent --show-error --max-time 15 --request "$method" --output "$response_output"
    --write-out '%{http_code}' --header 'Accept: application/json')
  [[ -z "$token" ]] || args+=(--header "Authorization: Bearer $token")
  [[ -z "$key" ]] || args+=(--header "Idempotency-Key: $key")
  if [[ -n "$body" ]]; then
    args+=(--header 'Content-Type: application/json' --data "$body")
  fi
  http_status="$(curl "${args[@]}" "${BASE_URL}${path}")"
}

check_health() {
  local status
  status="$(curl --silent --show-error --max-time 5 --output "$response_output" \
    --write-out '%{http_code}' "${MANAGEMENT_URL}/actuator/health")"
  if [[ "$status" != 200 ]] || ! jq -e '.status == "UP"' "$response_file" >/dev/null; then
    echo "application health failed: HTTP $status at $MANAGEMENT_URL" >&2
    jq -c . "$response_file" >&2 2>/dev/null || true
    exit 1
  fi
}

require_success() {
  local label="$1"
  if [[ ! "$http_status" =~ ^2[0-9][0-9]$ ]] || ! jq -e '.success == true' "$response_file" >/dev/null; then
    echo "$label failed: HTTP $http_status" >&2
    jq -c . "$response_file" >&2 2>/dev/null || true
    exit 1
  fi
}

require_value() {
  local expression="$1" expected="$2" label="$3"
  if ! jq -e --arg expected "$expected" "$expression == \$expected" "$response_file" >/dev/null; then
    echo "$label failed: expected $expected" >&2
    jq -c . "$response_file" >&2
    exit 1
  fi
}

login() {
  local phone="$1" code token
  request POST "/user/code?phone=${phone}"
  require_success "request local code for $phone"
  code="$(jq -er '.data.developmentCode' "$response_file")"
  request POST /user/login "" "$(jq -nc --arg phone "$phone" --arg code "$code" '{phone:$phone,code:$code}')"
  require_success "login for $phone"
  token="$(jq -er '.data | select(type == "string" and length == 32)' "$response_file")"
  printf '%s' "$token"
}

poll_order() {
  local token="$1" order_id="$2" expected="$3" deadline=$((SECONDS + POLL_TIMEOUT_SECONDS))
  while (( SECONDS <= deadline )); do
    request GET "/api/v1/orders/${order_id}" "$token"
    require_success "poll order $order_id"
    if [[ "$(jq -r '.data.status' "$response_file")" == "$expected" ]]; then
      return 0
    fi
    sleep "$POLL_INTERVAL_SECONDS"
  done
  echo "order $order_id did not reach $expected within ${POLL_TIMEOUT_SECONDS}s" >&2
  jq -c . "$response_file" >&2
  exit 1
}

echo "[1/8] Checking application health at $MANAGEMENT_URL"
check_health

echo "[2/8] Obtaining an ADMIN identity and three independent buyer identities"
admin_token="${DEMO_ADMIN_TOKEN:-}"
[[ -n "$admin_token" ]] || admin_token="$(login "$DEMO_ADMIN_PHONE")"
paid_token="$(login "$DEMO_PAID_PHONE")"
cancel_token="$(login "$DEMO_CANCEL_PHONE")"
timeout_token="$(login "$DEMO_TIMEOUT_PHONE")"

starts_at="$(date -u -d '+2 hours' +%Y-%m-%dT%H:%M:%SZ)"
ends_at="$(date -u -d '+4 hours' +%Y-%m-%dT%H:%M:%SZ)"
sales_start_at="$(date -u -d '-1 minute' +%Y-%m-%dT%H:%M:%SZ)"
sales_end_at="$(date -u -d '+1 hour' +%Y-%m-%dT%H:%M:%SZ)"

request POST /api/v1/admin/events "$admin_token" \
  "$(jq -nc --arg title "V1 demo $run_id" '{title:$title,description:"isolated checklist-7 demo",venue:"local-only"}')"
if [[ "$http_status" == 403 ]]; then
  echo "admin login is not allowlisted; start the app with ADMIN_USER_IDS containing this user's id, or pass DEMO_ADMIN_TOKEN" >&2
  exit 1
fi
require_success "create demo event"
event_id="$(jq -er '.data | numbers' "$response_file")"

request POST "/api/v1/admin/events/${event_id}/sessions" "$admin_token" \
  "$(jq -nc --arg starts "$starts_at" --arg ends "$ends_at" --arg salesStart "$sales_start_at" --arg salesEnd "$sales_end_at" \
    '{name:"V1 demo session",startsAt:$starts,endsAt:$ends,salesStartAt:$salesStart,salesEndAt:$salesEnd}')"
require_success "create demo session"
session_id="$(jq -er '.data | numbers' "$response_file")"

declare -a tier_ids=()
for name in paid cancel timeout; do
  request POST "/api/v1/admin/sessions/${session_id}/ticket-tiers" "$admin_token" \
    "$(jq -nc --arg name "$name" '{name:$name,unitPrice:8800,currency:"CNY",capacity:1}')"
  require_success "create $name tier"
  tier_ids+=("$(jq -er '.data | numbers' "$response_file")")
done
request POST "/api/v1/admin/events/${event_id}/publish" "$admin_token"
require_success "publish demo event"

echo "[3/8] Querying the published Event, session, and ticket tiers"
request GET "/api/v1/events/${event_id}"
require_success "query published event"
request GET "/api/v1/sessions/${session_id}/ticket-tiers"
require_success "query published ticket tiers"
[[ "$(jq -r '.data | length' "$response_file")" == 3 ]] \
  || { echo "expected three demo ticket tiers" >&2; jq -c . "$response_file" >&2; exit 1; }

create_order() {
  local token="$1" tier_id="$2" key="$3"
  request POST /api/v1/orders "$token" "$(jq -nc --argjson tier "$tier_id" '{ticketTierId:$tier,quantity:1}')" "$key"
  require_success "create order for tier $tier_id"
  jq -er '.data.id' "$response_file"
}

echo "[4/8] Creating, replaying, and querying the order that will be paid"
paid_key="item7-order-paid-${run_id}"
paid_order_id="$(create_order "$paid_token" "${tier_ids[0]}" "$paid_key")"
replayed_paid_order_id="$(create_order "$paid_token" "${tier_ids[0]}" "$paid_key")"
[[ "$paid_order_id" == "$replayed_paid_order_id" ]] || { echo "order replay created another order" >&2; exit 1; }
request GET "/api/v1/orders/${paid_order_id}" "$paid_token"
require_success "query pending order $paid_order_id"
require_value '.data.status' PENDING_PAYMENT "pending order status"

echo "[5/8] Paying normally and replaying the payment request"
payment_key="item7-payment-${run_id}"
request POST "/api/v1/orders/${paid_order_id}/payments" "$paid_token" "" "$payment_key"
require_success "pay order $paid_order_id"
require_value '.data.status' SUCCEEDED "payment status"
payment_id="$(jq -er '.data.id' "$response_file")"
request POST "/api/v1/orders/${paid_order_id}/payments" "$paid_token" "" "$payment_key"
require_success "replay payment $payment_id"
[[ "$(jq -er '.data.id' "$response_file")" == "$payment_id" ]] || { echo "payment replay created another payment" >&2; exit 1; }
poll_order "$paid_token" "$paid_order_id" PAID
request GET "/api/v1/payments/${payment_id}" "$paid_token"
require_success "query payment $payment_id"
require_value '.data.status' SUCCEEDED "persisted payment status"

echo "[6/8] Proving ownership isolation and user cancellation"
cancel_order_id="$(create_order "$cancel_token" "${tier_ids[1]}" "item7-order-cancel-${run_id}")"
request GET "/api/v1/orders/${cancel_order_id}" "$timeout_token"
[[ "$http_status" == 404 ]] || { echo "ownership check expected HTTP 404, got $http_status" >&2; exit 1; }
request POST "/api/v1/orders/${cancel_order_id}/cancel" "$cancel_token"
require_success "cancel order $cancel_order_id"
require_value '.data.status' CLOSED "canceled order status"
require_value '.data.closeReason' USER_CANCELED "canceled order reason"

echo "[7/8] Waiting for persistent expiry scanning to close an unpaid order"
timeout_order_id="$(create_order "$timeout_token" "${tier_ids[2]}" "item7-order-timeout-${run_id}")"
poll_order "$timeout_token" "$timeout_order_id" CLOSED
require_value '.data.closeReason' PAYMENT_EXPIRED "expired order reason"

echo "[8/8] Querying final order, payment, and inventory state"
request GET "/api/v1/orders/${paid_order_id}" "$paid_token"
require_success "query final paid order"
paid_order_json="$(jq -c '.data | {id,status,totalAmount,currency}' "$response_file")"
request GET "/api/v1/orders/${cancel_order_id}" "$cancel_token"
require_success "query final canceled order"
canceled_order_json="$(jq -c '.data | {id,status,closeReason}' "$response_file")"
request GET "/api/v1/orders/${timeout_order_id}" "$timeout_token"
require_success "query final expired order"
expired_order_json="$(jq -c '.data | {id,status,closeReason}' "$response_file")"
request GET "/api/v1/payments/${payment_id}" "$paid_token"
require_success "query final payment"
payment_json="$(jq -c '.data | {id,status,amount,currency,recoveryStatus}' "$response_file")"
request GET "/api/v1/sessions/${session_id}/ticket-tiers"
require_success "query final tier inventory"
final_counts="$(jq -er --argjson paid "${tier_ids[0]}" --argjson cancel "${tier_ids[1]}" --argjson timeout "${tier_ids[2]}" \
  '[.data[] | select(.id == $paid or .id == $cancel or .id == $timeout)]
  | {tiers:length,capacity:(map(.capacity)|add),available:(map(.available)|add),reserved:(map(.reserved)|add),allocated:(map(.allocated)|add)}' "$response_file")"
jq -e '.tiers == 3 and .capacity == 3 and .available == 2 and .reserved == 0 and .allocated == 1' \
  <<<"$final_counts" >/dev/null || { echo "final inventory mismatch: $final_counts" >&2; exit 1; }

echo "V1 demo passed"
echo "event=$event_id session=$session_id paidOrder=$paid_order_id canceledOrder=$cancel_order_id expiredOrder=$timeout_order_id"
echo "paidOrder=$paid_order_json"
echo "canceledOrder=$canceled_order_json"
echo "expiredOrder=$expired_order_json"
echo "payment=$payment_json"
echo "refund=none (the normal-payment, cancellation, and timeout paths do not create a refund)"
echo "finalInventory=$final_counts"
