#!/usr/bin/env bash
set -Eeuo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8081}"
MANAGEMENT_URL="${MANAGEMENT_URL:-http://127.0.0.1:8082}"
ITERATIONS="${ITERATIONS:-120}"
CONCURRENCY="${CONCURRENCY:-12}"
RESULT_FILE="${RESULT_FILE:-/tmp/event-trading-item7-baseline-$(date -u +%Y%m%dT%H%M%SZ).md}"
K6_BIN="${K6_BIN:-}"
JAVA_BIN="${JAVA_BIN:-}"
JQ_BIN="${JQ_BIN:-}"
CURL_BIN="${CURL_BIN:-}"

if [[ -z "$K6_BIN" ]]; then
  if command -v k6 >/dev/null 2>&1; then
    K6_BIN="$(command -v k6)"
  elif [[ -x "/mnt/c/Program Files/k6/k6.exe" ]]; then
    K6_BIN="/mnt/c/Program Files/k6/k6.exe"
  fi
fi
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
if [[ -z "$JAVA_BIN" ]]; then
  if command -v java >/dev/null 2>&1; then
    JAVA_BIN="$(command -v java)"
  elif [[ -x "/mnt/c/Program Files/Microsoft/jdk-21.0.7.6-hotspot/bin/java.exe" ]]; then
    JAVA_BIN="/mnt/c/Program Files/Microsoft/jdk-21.0.7.6-hotspot/bin/java.exe"
  fi
fi
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
native_curl_path() {
  if [[ "$CURL_BIN" == *.exe ]]; then wslpath -w "$1"; else printf '%s' "$1"; fi
}

for command in docker git awk mktemp; do
  command -v "$command" >/dev/null || { echo "missing required command: $command" >&2; exit 2; }
done
[[ "$ITERATIONS" =~ ^[0-9]+$ ]] && (( ITERATIONS >= 1 && ITERATIONS <= 200 )) \
  || { echo "ITERATIONS must be between 1 and 200" >&2; exit 2; }
[[ "$CONCURRENCY" =~ ^[0-9]+$ ]] && (( CONCURRENCY >= 1 && CONCURRENCY <= ITERATIONS )) \
  || { echo "CONCURRENCY must be between 1 and ITERATIONS" >&2; exit 2; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
mkdir -p "$repo_root/target"
work_dir="$(mktemp -d "$repo_root/target/item7-baseline.XXXXXX")"
trap 'rm -rf -- "$work_dir"' EXIT

health="$(curl --silent --show-error --max-time 3 --write-out $'\n%{http_code}' "$MANAGEMENT_URL/actuator/health")"
[[ "${health##*$'\n'}" == 200 ]] && jq -e '.status == "UP" or .success == true' <<<"${health%$'\n'*}" >/dev/null \
  || { echo "application is not healthy at $MANAGEMENT_URL" >&2; exit 1; }

start_status="$(git status --short --branch --untracked-files=all)"
legacy_before="$(docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --batch --skip-column-names -uroot event_trading' \
  <<<"SELECT (SELECT COUNT(*) FROM tb_order_request),(SELECT COUNT(*) FROM tb_voucher_order),(SELECT COUNT(*) FROM tb_outbox_event);")"

echo "Preparing isolated event/tier 9900070001/9900070003"
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --batch -uroot event_trading' \
  < loadtest/seed-event-trading-baseline.sql >/dev/null
token_script="$(<loadtest/seed-event-trading-baseline-tokens.lua)"
seeded="$(docker compose exec -T redis redis-cli --raw EVAL "$token_script" 0 "$ITERATIONS")"
[[ "$seeded" == "$ITERATIONS" ]] || { echo "expected $ITERATIONS Redis sessions, got $seeded" >&2; exit 1; }

run_tag="$(date -u +%Y%m%dT%H%M%SZ)-$$"
run_one() {
  local index="$1" token order_key payment_key body status order_id payment_id
  local order_time=0 payment_time=0 query_time=0 accepted=0 payment_http=0 final=0 rejected=0 technical=0 requests=1
  token="$(printf '%032x' $((100000 + index)))"
  order_key="item7-order-${run_tag}-${index}"
  payment_key="item7-payment-${run_tag}-${index}"

  status="$(curl --silent --show-error --max-time 15 -o "$(native_curl_path "$work_dir/order-$index.json")" -w '%{http_code} %{time_total}' \
    -H "Authorization: Bearer $token" -H "Idempotency-Key: $order_key" -H 'Content-Type: application/json' \
    --data '{"ticketTierId":9900070003,"quantity":1}' "$BASE_URL/api/v1/orders")" || status="000 0"
  read -r order_http order_time <<<"$status"
  if [[ "$order_http" == 200 ]] && order_id="$(jq -er '.data.id | numbers' "$work_dir/order-$index.json" 2>/dev/null)"; then
    accepted=1
  elif [[ "$order_http" == 409 ]]; then
    rejected=1
  else
    technical=1
  fi

  if (( accepted )); then
    requests=$((requests + 1))
    status="$(curl --silent --show-error --max-time 15 -o "$(native_curl_path "$work_dir/payment-$index.json")" -w '%{http_code} %{time_total}' \
      -X POST -H "Authorization: Bearer $token" -H "Idempotency-Key: $payment_key" \
      "$BASE_URL/api/v1/orders/$order_id/payments")" || status="000 0"
    read -r payment_status payment_time <<<"$status"
    if [[ "$payment_status" == 200 ]] \
        && jq -e '.success == true and .data.status == "SUCCEEDED"' "$work_dir/payment-$index.json" >/dev/null 2>&1; then
      payment_http=1
      payment_id="$(jq -r '.data.id' "$work_dir/payment-$index.json")"
    else
      technical=1
    fi

    requests=$((requests + 1))
    status="$(curl --silent --show-error --max-time 15 -o "$(native_curl_path "$work_dir/query-$index.json")" -w '%{http_code} %{time_total}' \
      -H "Authorization: Bearer $token" "$BASE_URL/api/v1/orders/$order_id")" || status="000 0"
    read -r query_status query_time <<<"$status"
    if [[ "$query_status" == 200 ]] \
        && jq -e '.success == true and .data.status == "PAID"' "$work_dir/query-$index.json" >/dev/null 2>&1; then
      final=1
    else
      technical=1
    fi
  fi
  printf '%d\t%d\t%d\t%d\t%d\t%d\t%s\t%s\t%s\n' "$accepted" "$payment_http" "$final" "$rejected" "$technical" "$requests" \
    "$order_time" "$payment_time" "$query_time" > "$work_dir/result-$index.tsv"
}
export -f run_one
export -f jq
export -f curl
export -f native_curl_path
export BASE_URL run_tag work_dir JQ_BIN CURL_BIN

started_epoch="$(date +%s)"
for ((index=1; index<=ITERATIONS; index++)); do
  run_one "$index" &
  while (( $(jobs -pr | wc -l) >= CONCURRENCY )); do wait -n || true; done
done
wait
finished_results="$(find "$work_dir" -maxdepth 1 -name 'result-*.tsv' -type f | wc -l)"
dropped_iterations=$((ITERATIONS - finished_results))
elapsed_seconds=$(( $(date +%s) - started_epoch ))

summary="$(awk -F '\t' '{a+=$1;p+=$2;f+=$3;r+=$4;t+=$5;h+=$6;ot+=$7;pt+=$8;qt+=$9}
  END {printf "%d\t%d\t%d\t%d\t%d\t%d\t%.6f\t%.6f\t%.6f",a,p,f,r,t,h,ot/NR,pt/NR,qt/NR}' "$work_dir"/result-*.tsv)"
IFS=$'\t' read -r accepted payments final_success rejected technical http_requests order_avg payment_avg query_avg <<<"$summary"

audit="$( { printf 'SET @expected=%d;\n' "$ITERATIONS"; cat loadtest/audit-event-trading-baseline.sql; } \
  | docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --batch --skip-column-names -uroot event_trading' )"
IFS=$'\t' read -r capacity available reserved allocated db_orders db_paid db_payments db_succeeded provider_effects refunds conserved nonnegative reservation_match no_duplicate exact <<<"$audit"

legacy_after="$(docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --batch --skip-column-names -uroot event_trading' \
  <<<"SELECT (SELECT COUNT(*) FROM tb_order_request),(SELECT COUNT(*) FROM tb_voucher_order),(SELECT COUNT(*) FROM tb_outbox_event);")"
legacy_unchanged=0
[[ "$legacy_before" == "$legacy_after" ]] && legacy_unchanged=1

sha="$(git rev-parse HEAD)"
branch="$(git branch --show-current)"
end_status="$(git status --short --branch --untracked-files=all)"
dirty_count="$(git status --porcelain --untracked-files=all | wc -l)"
java_version="${JAVA_VERSION_OVERRIDE:-}"
[[ -n "$java_version" || -z "$JAVA_BIN" ]] || java_version="$("$JAVA_BIN" -version 2>&1 | head -n 1 | tr -d '\r' || true)"
[[ -n "$java_version" ]] || java_version="unavailable to the baseline runner; record the IDEA runtime separately"
k6_version=""
[[ -z "$K6_BIN" ]] || k6_version="$("$K6_BIN" version 2>&1 | head -n 1 || true)"
[[ -n "$k6_version" ]] || k6_version="installed path not visible to the baseline runner"
mysql_version="$(docker compose exec -T mysql mysql --version | tr -d '\r')"
redis_version="$(docker compose exec -T redis redis-server --version | tr -d '\r')"
docker_version="$(docker version --format 'client={{.Client.Version}} server={{.Server.Version}}')"
compose_services="$(docker compose ps --services --status running | paste -sd, -)"
machine="$(uname -srmo); CPU=$(lscpu | awk -F: '/Model name/{gsub(/^[ \t]+/,"",$2); print $2; exit}'); logicalCPUs=$(nproc); memory=$(free -h | awk '/^Mem:/{print $2}')"
mkdir -p "$(dirname "$RESULT_FILE")"
{
  echo "# Event trading HTTP baseline"
  echo
  echo "- UTC time: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "- Git: \`$sha\` on \`$branch\`; uncommitted paths: $dirty_count"
  echo "- Machine: $machine"
  echo "- Versions: Java $java_version; $mysql_version; $redis_version; Docker $docker_version; Compose $(docker compose version --short); k6 $k6_version (version recorded; bounded-worker driver is curl)"
  echo "- Running Compose services: $compose_services (expected: mysql,redis; RabbitMQ is not part of this run)"
  echo "- Data preparation: scoped reseed of Event/session/tier IDs 9900070001-9900070003 plus $ITERATIONS isolated Redis sessions; no database, Redis, queue, or volume reset"
  echo "- Workload: $ITERATIONS complete-trade attempts, $CONCURRENCY bounded concurrent workers, one isolated tier with capacity 200, wall time ${elapsed_seconds}s"
  echo "- Path measured: authenticated HTTP order creation -> simulated payment -> owned order query"
  echo "- HTTP requests: $http_requests; order requests accepted: $accepted; payment responses succeeded: $payments"
  echo "- Final correct writes (order PAID): $final_success; business rejection: $rejected; technical failures: $technical; dropped iterations: $dropped_iterations"
  echo "- Mean HTTP seconds: order=$order_avg, payment=$payment_avg, final-query=$query_avg"
  echo "- Database: capacity=$capacity, available=$available, reserved=$reserved, allocated=$allocated, orders=$db_orders, paid=$db_paid, payments=$db_payments, succeededPayments=$db_succeeded, providerChargeEffects=$provider_effects, refunds=$refunds"
  echo "- Checks: inventoryConserved=$conserved, inventoryNonnegative=$nonnegative, reservationOrderMatch=$reservation_match, noDuplicateIdempotencyResult=$no_duplicate, exactExpectedResult=$exact"
  echo "- Legacy-path isolation: before=$legacy_before; after=$legacy_after; unchanged=$legacy_unchanged"
  echo
  echo "## Worktree at start"
  echo
  echo '```text'
  [[ -z "$start_status" ]] && echo '(clean)' || echo "$start_status"
  echo '```'
  echo
  echo "## Worktree at end"
  echo
  echo '```text'
  [[ -z "$end_status" ]] && echo '(clean)' || echo "$end_status"
  echo '```'
  echo
  echo "HTTP success means the command returned 200 with the expected response payload. Final business success additionally requires a PAID order and the database checks above. This is a moderate local baseline, not a capacity limit, SLA, soak test, or real-funds benchmark."
} > "$RESULT_FILE"

if (( accepted != ITERATIONS || payments != ITERATIONS || final_success != ITERATIONS || rejected != 0 || technical != 0 || dropped_iterations != 0 )) \
    || [[ "$conserved" != 1 || "$nonnegative" != 1 || "$reservation_match" != 1 || "$no_duplicate" != 1 \
      || "$exact" != 1 || "$legacy_unchanged" != 1 ]]; then
  echo "baseline failed; evidence: $RESULT_FILE" >&2
  exit 1
fi
echo "baseline passed; evidence: $RESULT_FILE"
