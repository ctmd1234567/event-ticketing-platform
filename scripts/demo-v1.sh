#!/usr/bin/env bash
set -Eeuo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8081}"
MANAGEMENT_URL="${MANAGEMENT_URL:-http://127.0.0.1:8082}"
DEMO_ADMIN_PHONE="${DEMO_ADMIN_PHONE:-13900007001}"
DEMO_BUYER_PHONE="${DEMO_BUYER_PHONE:-13900007002}"
CURL_BIN="${CURL_BIN:-}"
JQ_BIN="${JQ_BIN:-}"

if [[ -z "$CURL_BIN" ]]; then
  CURL_BIN="$(command -v curl || command -v curl.exe || true)"
fi
if [[ -z "$JQ_BIN" ]]; then
  JQ_BIN="$(command -v jq || command -v jq.exe || true)"
fi
[[ -n "$CURL_BIN" ]] || { echo "missing required command: curl (or set CURL_BIN)" >&2; exit 2; }
[[ -n "$JQ_BIN" ]] || { echo "missing required command: jq (or set JQ_BIN)" >&2; exit 2; }

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

echo "[1/7] Checking application health"
health_status="$(curl_cmd --silent --show-error --max-time 5 --output "$response_output" \
  --write-out '%{http_code}' "${MANAGEMENT_URL}/actuator/health")"
if [[ "$health_status" != 200 ]] || ! jq_cmd -e '.status == "UP"' "$response_file" >/dev/null; then
  echo "application health failed: HTTP $health_status at $MANAGEMENT_URL" >&2
  jq_cmd -c . "$response_file" >&2 2>/dev/null || true
  exit 1
fi

echo "[2/7] Authenticating one administrator and one buyer"
admin_token="${DEMO_ADMIN_TOKEN:-}"
[[ -n "$admin_token" ]] || admin_token="$(login "$DEMO_ADMIN_PHONE")"
buyer_token="$(login "$DEMO_BUYER_PHONE")"

starts_at="$(date -u -d '+2 hours' +%Y-%m-%dT%H:%M:%SZ)"
ends_at="$(date -u -d '+4 hours' +%Y-%m-%dT%H:%M:%SZ)"
sales_start_at="$(date -u -d '-1 minute' +%Y-%m-%dT%H:%M:%SZ)"
sales_end_at="$(date -u -d '+1 hour' +%Y-%m-%dT%H:%M:%SZ)"

echo "[3/7] Creating and publishing an isolated Event"
request POST /api/v1/admin/events "$admin_token" \
  "$(jq_cmd -nc --arg title "V1 demo $run_id" '{title:$title,description:"HTTP happy-path demo",venue:"local-demo"}')"
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

request POST "/api/v1/admin/sessions/${session_id}/ticket-tiers" "$admin_token" \
  "$(jq_cmd -nc '{name:"standard",unitPrice:8800,currency:"CNY",capacity:1}')"
require_success "create demo ticket tier"
tier_id="$(jq_cmd -er '.data | numbers' "$response_file")"

request POST "/api/v1/admin/events/${event_id}/publish" "$admin_token"
require_success "publish demo event"

echo "[4/7] Querying the published Event and inventory"
request GET "/api/v1/events/${event_id}"
require_success "query published event"
request GET "/api/v1/sessions/${session_id}/ticket-tiers"
require_success "query published ticket tier"
jq_cmd -e --argjson tier "$tier_id" \
  '.data[] | select(.id == $tier and .capacity == 1 and .available == 1 and .reserved == 0 and .allocated == 0)' \
  "$response_file" >/dev/null || { echo "initial inventory mismatch" >&2; jq_cmd -c . "$response_file" >&2; exit 1; }

echo "[5/7] Creating, replaying, and querying a pending order"
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

echo "[6/7] Paying normally and replaying the payment request"
payment_key="v1-demo-payment-${run_id}"
request POST "/api/v1/orders/${order_id}/payments" "$buyer_token" "" "$payment_key"
require_success "pay order"
payment_id="$(jq_cmd -er '.data.id' "$response_file")"
jq_cmd -e '.data.status == "SUCCEEDED"' "$response_file" >/dev/null \
  || { echo "payment did not succeed" >&2; jq_cmd -c . "$response_file" >&2; exit 1; }
request POST "/api/v1/orders/${order_id}/payments" "$buyer_token" "" "$payment_key"
require_success "replay payment"
[[ "$(jq_cmd -er '.data.id' "$response_file")" == "$payment_id" ]] || { echo "payment replay created another payment" >&2; exit 1; }

echo "[7/7] Querying final order, payment, and allocated inventory"
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

echo "V1 HTTP demo passed"
echo "event=$event_id session=$session_id tier=$tier_id order=$order_id payment=$payment_id"
echo "order=$order_json"
echo "payment=$payment_json"
echo "inventory=$inventory_json"
