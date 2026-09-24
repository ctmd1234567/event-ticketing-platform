#!/usr/bin/env python3
"""Bounded Event HTTP workload against disposable MySQL/Redis containers.

The fixture uses fresh IDs and never deletes rows. Use only against an isolated
environment: it creates real orders and authentication sessions.
"""

import argparse
import concurrent.futures
import json
import math
import os
import subprocess
import threading
import time
import urllib.error
import urllib.request


def docker(args, data=None):
    return subprocess.run([DOCKER, *args], input=data, text=True, capture_output=True,
                          check=True).stdout.strip()


def sql(statement):
    return docker(["exec", "-i", MYSQL, "sh", "-c",
                   'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --batch --skip-column-names -uroot event_trading'],
                  statement + "\n")


def status():
    rows = sql("SHOW GLOBAL STATUS WHERE Variable_name IN "
               "('Innodb_row_lock_waits','Innodb_row_lock_time');")
    values = {line.split("\t")[0]: int(line.split("\t")[1]) for line in rows.splitlines() if "\t" in line}
    deadlocks = sql("SELECT COUNT FROM information_schema.INNODB_METRICS WHERE NAME='lock_deadlocks';")
    values["lock_deadlocks"] = int(deadlocks)
    return values


def percentile(values, n):
    if not values:
        return None
    values.sort()
    return round(values[max(0, math.ceil(len(values) * n / 100) - 1)], 4)


def app_metrics(url):
    wanted = {"event_order_writes_total", "event_order_create_duration_seconds_count",
              "event_order_create_duration_seconds_sum", "hikaricp_connections_pending"}
    with urllib.request.urlopen(url + "/actuator/prometheus", timeout=5) as response:
        lines = response.read().decode().splitlines()
    values = {}
    for line in lines:
        if line.startswith("#") or not line:
            continue
        name = line.split("{", 1)[0].split(" ", 1)[0]
        if name in wanted:
            values[name] = float(line.rsplit(" ", 1)[1])
    return values

def request(index, tier, user_base, run_id, base_url):
    user = user_base + index
    token = f"{user:032x}"
    body = json.dumps({"ticketTierId": tier, "quantity": 1}).encode()
    req = urllib.request.Request(base_url + "/api/v1/orders", data=body, method="POST",
                                 headers={"Authorization": "Bearer " + token,
                                          "Idempotency-Key": f"item11-{run_id}-{index:06d}",
                                          "Content-Type": "application/json"})
    started = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            code, payload = response.status, response.read()
    except urllib.error.HTTPError as error:
        code, payload = error.code, error.read()
    except Exception as error:
        return {"code": 0, "latency": time.monotonic() - started, "error": type(error).__name__}
    try:
        body = json.loads(payload)
        valid = (body.get("success") is True and body.get("data", {}).get("status") == "PENDING_PAYMENT")
    except (ValueError, AttributeError):
        valid = False
    return {"code": code, "latency": time.monotonic() - started, "success": code == 200 and valid}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=["normal", "stress", "spike", "multi"], required=True)
    parser.add_argument("--base-url", default="http://127.0.0.1:8083")
    parser.add_argument("--management-url", default="http://127.0.0.1:8084")
    parser.add_argument("--rate", type=float, required=True, help="scheduled requests per second")
    parser.add_argument("--duration", type=float, required=True, help="seconds")
    parser.add_argument("--workers", type=int, required=True)
    parser.add_argument("--tiers", type=int, default=1)
    parser.add_argument("--capacity", type=int, default=1000, help="capacity per tier")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    if not (0 < args.rate <= 500 and 0 < args.duration <= 120 and 1 <= args.workers <= 100
            and 1 <= args.tiers <= 16 and 1 <= args.capacity <= 10000):
        parser.error("rate/duration/workers/tiers/capacity exceed bounded fixture limits")
    planned = math.ceil(args.rate * args.duration)
    if planned > 1000:
        parser.error("at most 1000 users per run")
    run_id = str(int(time.time() * 1000))
    base = 4_000_000_000_000_000 + int(run_id) * 1000
    user_base = base + 1000
    tier_ids = [base + 10 + i for i in range(args.tiers)]
    fixture = [f"INSERT INTO et_event(id,title,venue,status,created_by) VALUES ({base+1},'Item 11 {args.scenario}','isolated','PUBLISHED',1);",
               f"INSERT INTO et_event_session(id,event_id,name,starts_at,ends_at,sales_start_at,sales_end_at,status) VALUES ({base+2},{base+1},'Item 11',NOW()+INTERVAL 2 HOUR,NOW()+INTERVAL 4 HOUR,NOW()-INTERVAL 1 MINUTE,NOW()+INTERVAL 1 HOUR,'ON_SALE');"]
    for i, tier in enumerate(tier_ids):
        fixture.append(f"INSERT INTO et_ticket_tier(id,session_id,name,unit_price,currency,capacity,available,reserved,allocated,purchase_limit_per_user,status) VALUES ({tier},{base+2},'tier-{i}',8800,'CNY',{args.capacity},{args.capacity},0,0,1,'ON_SALE');")
    sql("\n".join(fixture))
    lua = "for i=0,tonumber(ARGV[2])-1 do local u=tonumber(ARGV[1])+i; local k='login:token:'..string.format('%032x',u); redis.call('HSET',k,'id',string.format('%.0f',u),'nickName','item11'); redis.call('EXPIRE',k,3600) end; return tonumber(ARGV[2])"
    seeded = docker(["exec", REDIS, "redis-cli", "--raw", "EVAL", lua, "0", str(user_base), str(planned)])
    if seeded != str(planned):
        raise RuntimeError(f"session seed mismatch: {seeded}")
    before = status()
    app_before = app_metrics(args.management_url)
    slots = threading.BoundedSemaphore(args.workers)
    results = []
    futures = []
    dropped = 0
    started = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        for index in range(planned):
            due = started + index / args.rate
            delay = due - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            if not slots.acquire(blocking=False):
                dropped += 1
                continue
            future = pool.submit(request, index, tier_ids[index % len(tier_ids)], user_base,
                                 run_id, args.base_url)
            future.add_done_callback(lambda _: slots.release())
            futures.append(future)
        results = [future.result() for future in futures]
    elapsed = time.monotonic() - started
    after = status()
    app_after = app_metrics(args.management_url)
    db = sql(f"SELECT COUNT(*),COALESCE(SUM(status='PENDING_PAYMENT'),0) FROM et_order WHERE event_id={base+1};")
    inventory = sql(f"SELECT id,capacity,available,reserved,allocated,available+reserved+allocated=capacity FROM et_ticket_tier WHERE session_id={base+2} ORDER BY id;")
    reservations = sql(f"SELECT COUNT(*) FROM et_inventory_reservation WHERE ticket_tier_id IN ({','.join(map(str,tier_ids))});")
    inventory_rows = [[int(value) for value in row.split("\t")] for row in inventory.splitlines()]
    successes = sum(result.get("success", False) for result in results)
    conflicts = sum(result["code"] == 409 for result in results)
    technical = len(results) - successes - conflicts
    inventory_ok = (len(inventory_rows) == args.tiers
                    and all(row[5] == 1 and min(row[2:5]) >= 0 for row in inventory_rows)
                    and sum(row[3] for row in inventory_rows) == successes
                    and int(reservations) == successes)
    report = {"scenario": args.scenario, "runId": run_id, "fixtureEventId": base+1,
              "tiers": tier_ids, "scheduled": planned, "sent": len(results), "dropped": dropped,
              "targetRate": args.rate, "workers": args.workers, "elapsedSeconds": round(elapsed, 3),
              "httpSuccess": successes, "businessConflicts": conflicts, "technicalFailures": technical,
              "httpCodes": {str(code): sum(result["code"] == code for result in results)
                            for code in sorted(set(result["code"] for result in results))},
              "httpLatencySeconds": {"p50": percentile([r["latency"] for r in results], 50),
                                     "p95": percentile([r["latency"] for r in results], 95),
                                     "p99": percentile([r["latency"] for r in results], 99)},
              "mysqlLockStatusDelta": {key: after.get(key, 0) - before.get(key, 0)
                                       for key in sorted(set(before) | set(after))},
              "appMetricDelta": {key: round(app_after.get(key, 0) - app_before.get(key, 0), 6)
                                 for key in sorted(set(app_before) | set(app_after))
                                 if key != "hikaricp_connections_pending"},
              "hikariPendingAfter": app_after.get("hikaricp_connections_pending"),
              "dbOrdersAndPending": db, "reservations": int(reservations),
              "inventoryConservedAndMatched": inventory_ok, "inventory": inventory,
              "technicalErrorSamples": [r.get("error") for r in results if r.get("error")][:5]}
    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as output:
        json.dump(report, output, indent=2)
        output.write("\n")
    print(json.dumps(report, indent=2))
    db_orders, db_pending = map(int, db.split("\t"))
    if technical or successes != db_orders or db_pending != successes or not inventory_ok or dropped:
        raise SystemExit(1)


if __name__ == "__main__":
    DOCKER = os.environ.get("DOCKER_BIN", "docker")
    MYSQL = os.environ.get("ITEM11_MYSQL_CONTAINER", "event-item11-mysql")
    REDIS = os.environ.get("ITEM11_REDIS_CONTAINER", "event-item11-redis")
    main()
