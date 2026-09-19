#!/usr/bin/env bash
set -Eeuo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8081}"
MANAGEMENT_URL="${MANAGEMENT_URL:-http://127.0.0.1:8082}"
ATTEMPTS="${ATTEMPTS:-200}"
CAPACITY="${CAPACITY:-100}"
CONCURRENCY="${CONCURRENCY:-32}"
RESULT_FILE="${RESULT_FILE:-/tmp/event-order-creation-baseline-$(date -u +%Y%m%dT%H%M%SZ).md}"
JQ_BIN="${JQ_BIN:-}"
CURL_BIN="${CURL_BIN:-}"

if [[ -z "$CURL_BIN" ]]; then
  if command -v curl >/dev/null 2>&1; then
    CURL_BIN="$(command -v curl)"
  elif command -v curl.exe >/dev/null 2>&1; then
    CURL_BIN="$(command -v curl.exe)"
  else
    echo "missing required command: curl (or set CURL_BIN)" >&2
    exit 2
  fi
fi
if [[ -z "$JQ_BIN" ]]; then
  if command -v jq >/dev/null 2>&1; then
    JQ_BIN="$(command -v jq)"
  elif command -v jq.exe >/dev/null 2>&1; then
    JQ_BIN="$(command -v jq.exe)"
  else
    echo "missing required command: jq (or set JQ_BIN)" >&2
    exit 2
  fi
fi
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
native_path() {
  if [[ "$CURL_BIN" == *.exe ]]; then wslpath -w "$1"; else printf '%s' "$1"; fi
}

for command in docker git awk sort sed mktemp; do
  command -v "$command" >/dev/null || { echo "missing required command: $command" >&2; exit 2; }
done
[[ "$ATTEMPTS" =~ ^[0-9]+$ ]] && (( ATTEMPTS >= 1 && ATTEMPTS <= 1000 )) \
  || { echo "ATTEMPTS must be between 1 and 1000" >&2; exit 2; }
[[ "$CAPACITY" =~ ^[0-9]+$ ]] && (( CAPACITY >= 1 && CAPACITY <= ATTEMPTS )) \
  || { echo "CAPACITY must be between 1 and ATTEMPTS" >&2; exit 2; }
[[ "$CONCURRENCY" =~ ^[0-9]+$ ]] && (( CONCURRENCY >= 1 && CONCURRENCY <= ATTEMPTS )) \
  || { echo "CONCURRENCY must be between 1 and ATTEMPTS" >&2; exit 2; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
mkdir -p "$repo_root/target"
work_dir="$(mktemp -d "$repo_root/target/event-order-baseline.XXXXXX")"
trap 'rm -rf -- "$work_dir"' EXIT

health="$(curl_cmd --silent --show-error --max-time 3 --write-out $'\n%{http_code}' "$MANAGEMENT_URL/actuator/health")"
[[ "${health##*$'\n'}" == 200 ]] && jq_cmd -e '.status == "UP"' <<<"${health%$'\n'*}" >/dev/null \
  || { echo "application is not healthy at $MANAGEMENT_URL" >&2; exit 1; }

legacy_before="$(docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --batch --skip-column-names -uroot event_trading' \
  <<<"SELECT (SELECT COUNT(*) FROM tb_order_request),(SELECT COUNT(*) FROM tb_voucher_order),(SELECT COUNT(*) FROM tb_outbox_event);")"

echo "Preparing isolated Event order fixture: attempts=$ATTEMPTS capacity=$CAPACITY concurrency=$CONCURRENCY"
{ printf 'SET @capacity=%d;\n' "$CAPACITY"; cat loadtest/seed-event-trading-baseline.sql; } \
  | docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --batch -uroot event_trading' >/dev/null
token_script="$(<loadtest/seed-event-trading-baseline-tokens.lua)"
seeded="$(docker compose exec -T redis redis-cli --raw EVAL "$token_script" 0 "$ATTEMPTS")"
[[ "$seeded" == "$ATTEMPTS" ]] || { echo "expected $ATTEMPTS Redis sessions, got $seeded" >&2; exit 1; }

run_tag="$(date -u +%Y%m%dT%H%M%SZ)-$$"
run_one() {
  local index="$1" token status http_status elapsed accepted=0 conflict=0 technical=0
  token="$(printf '%032x' $((100000 + index)))"
  status="$(curl_cmd --silent --show-error --max-time 15 \
    --output "$(native_path "$work_dir/order-$index.json")" --write-out '%{http_code} %{time_total}' \
    --header "Authorization: Bearer $token" \
    --header "Idempotency-Key: event-order-baseline-${run_tag}-${index}" \
    --header 'Content-Type: application/json' \
    --data '{"ticketTierId":9900070003,"quantity":1}' \
    "$BASE_URL/api/v1/orders")" || status="000 0"
  read -r http_status elapsed <<<"$status"
  if [[ "$http_status" == 200 ]] \
      && jq_cmd -e '.success == true and .data.status == "PENDING_PAYMENT"' "$work_dir/order-$index.json" >/dev/null 2>&1; then
    accepted=1
  elif [[ "$http_status" == 409 ]] \
      && jq_cmd -e '.success == false' "$work_dir/order-$index.json" >/dev/null 2>&1; then
    conflict=1
  else
    technical=1
  fi
  printf '%d\t%d\t%d\t%s\t%s\n' "$accepted" "$conflict" "$technical" "$http_status" "$elapsed" \
    > "$work_dir/result-$index.tsv"
}
export -f run_one curl_cmd jq_cmd native_path
export BASE_URL run_tag work_dir JQ_BIN CURL_BIN

started_epoch="$(date +%s)"
for ((index=1; index<=ATTEMPTS; index++)); do
  run_one "$index" &
  while (( $(jobs -pr | wc -l) >= CONCURRENCY )); do wait -n || true; done
done
wait
elapsed_seconds=$(( $(date +%s) - started_epoch ))
finished_results="$(find "$work_dir" -maxdepth 1 -name 'result-*.tsv' -type f | wc -l)"
dropped_attempts=$((ATTEMPTS - finished_results))

summary="$(awk -F '\t' '{a+=$1;c+=$2;t+=$3} END {printf "%d\t%d\t%d",a,c,t}' "$work_dir"/result-*.tsv)"
IFS=$'\t' read -r accepted conflicts technical <<<"$summary"
cut -f5 "$work_dir"/result-*.tsv | sort -n > "$work_dir/latencies.txt"
percentile() {
  local percent="$1" count index
  count="$(wc -l < "$work_dir/latencies.txt")"
  index=$(( (count * percent + 99) / 100 ))
  sed -n "${index}p" "$work_dir/latencies.txt"
}
p50="$(percentile 50)"
p95="$(percentile 95)"
p99="$(percentile 99)"

expected_success=$(( ATTEMPTS < CAPACITY ? ATTEMPTS : CAPACITY ))
expected_conflicts=$(( ATTEMPTS - expected_success ))
audit="$( { printf 'SET @expected_success=%d;\n' "$expected_success"; cat loadtest/audit-event-trading-baseline.sql; } \
  | docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --batch --skip-column-names -uroot event_trading' )"
IFS=$'\t' read -r capacity available reserved allocated db_orders db_pending db_buyers db_reservations db_reserved \
  conserved nonnegative reservation_match no_duplicate_orders no_duplicate_reservations exact <<<"$audit"

legacy_after="$(docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --batch --skip-column-names -uroot event_trading' \
  <<<"SELECT (SELECT COUNT(*) FROM tb_order_request),(SELECT COUNT(*) FROM tb_voucher_order),(SELECT COUNT(*) FROM tb_outbox_event);")"
legacy_unchanged=0
[[ "$legacy_before" == "$legacy_after" ]] && legacy_unchanged=1
oversold=1
[[ "$conserved" == 1 && "$nonnegative" == 1 && "$db_orders" -le "$capacity" ]] && oversold=0

sha="$(git rev-parse HEAD)"
branch="$(git branch --show-current)"
mysql_version="$(docker compose exec -T mysql mysql --version | tr -d '\r')"
redis_version="$(docker compose exec -T redis redis-server --version | tr -d '\r')"
docker_version="$(docker version --format 'client={{.Client.Version}} server={{.Server.Version}}')"
compose_services="$(docker compose ps --services --status running | paste -sd, -)"
machine="$(uname -srmo); CPU=$(lscpu | awk -F: '/Model name/{gsub(/^[ \t]+/,"",$2); print $2; exit}'); logicalCPUs=$(nproc); memory=$(free -h | awk '/^Mem:/{print $2}')"
mkdir -p "$(dirname "$RESULT_FILE")"
{
  echo "# Event order creation baseline"
  echo
  echo "- UTC time: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "- Git: \`$sha\` on \`$branch\`"
  echo "- Machine: $machine"
  echo "- Infrastructure: $mysql_version; $redis_version; Docker $docker_version; Compose $(docker compose version --short)"
  echo "- Running Compose services: $compose_services (expected: mysql,redis)"
  echo "- Fixture: $ATTEMPTS isolated authenticated users, one published Event/TicketTier, capacity $CAPACITY"
  echo "- Load model: $ATTEMPTS total POST /api/v1/orders requests, $CONCURRENCY bounded concurrent workers, wall time ${elapsed_seconds}s"
  echo "- Results: success=$accepted; businessConflicts=$conflicts; technicalFailures=$technical; droppedAttempts=$dropped_attempts"
  echo "- HTTP latency seconds (all order requests): p50=$p50; p95=$p95; p99=$p99"
  echo "- Database: capacity=$capacity; available=$available; reserved=$reserved; allocated=$allocated; orders=$db_orders; pendingOrders=$db_pending; buyers=$db_buyers; reservations=$db_reservations; reservedReservations=$db_reserved"
  echo "- Checks: oversold=$oversold; inventoryConserved=$conserved; inventoryNonnegative=$nonnegative; reservationOrderMatch=$reservation_match; noDuplicateEffectiveOrder=$no_duplicate_orders; noDuplicateReservation=$no_duplicate_reservations; exactExpectedResult=$exact"
  echo "- Legacy-path isolation: before=$legacy_before; after=$legacy_after; unchanged=$legacy_unchanged"
  echo
  echo "This measures authenticated Event order creation only. It is a bounded local baseline, not a capacity limit, payment benchmark, production SLA, or soak test."
} > "$RESULT_FILE"

if (( accepted != expected_success || conflicts != expected_conflicts || technical != 0 || dropped_attempts != 0 || oversold != 0 )) \
    || [[ "$conserved" != 1 || "$nonnegative" != 1 || "$reservation_match" != 1 \
      || "$no_duplicate_orders" != 1 || "$no_duplicate_reservations" != 1 || "$exact" != 1 \
      || "$legacy_unchanged" != 1 ]]; then
  echo "baseline failed; evidence: $RESULT_FILE" >&2
  exit 1
fi
echo "baseline passed; evidence: $RESULT_FILE"
