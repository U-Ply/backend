#!/usr/bin/env bash
#
# End-to-end acceptance run for one round.
#
#   ROUND=V1 ./scripts/test/acceptance.sh
#   ROUND=V3 REQUESTS=200 ./scripts/test/acceptance.sh
#
# The script owns the whole chain so no step can be skipped by accident:
#
#   build -> start -> assert strategy -> reset -> warm redis -> issue
#         -> wait kafka lag 0 -> verify -> reconcile -> report -> stop
#
# Every gate fails loudly. A green run means every step actually ran,
# not that a step was silently skipped.

set -uo pipefail

# ------------------------------------------------------------------ config

ROUND="${ROUND:-V1}"
RUN_ID="${RUN_ID:-}"

CAMPAIGN_ID="${CAMPAIGN_ID:-31}"
STOCK_ID="${STOCK_ID:-301}"
ROUTE_ID="${ROUTE_ID:-JEJU}"
FARE_CLASS="${FARE_CLASS:-ECONOMY}"
INITIAL_STOCK="${INITIAL_STOCK:-30}"
REQUESTS="${REQUESTS:-100}"

FAIL_ON_VIOLATION="${FAIL_ON_VIOLATION:-true}"
KEEP_APP="${KEEP_APP:-false}"
SKIP_BUILD="${SKIP_BUILD:-false}"

BASE="${BASE:-http://localhost:8081}"
JAR="${JAR:-build/libs/coupon-service-0.0.1-SNAPSHOT.jar}"
OUT_DIR="${OUT_DIR:-docs/round-results}"
LOG="build/acceptance-app.log"

MYSQL_C="${MYSQL_C:-coupon-mysql}"
REDIS_C="${REDIS_C:-coupon-redis}"
KAFKA_C="${KAFKA_C:-coupon-kafka}"
DB_USER="${DB_USER:-root}"
DB_PASS="${DB_PASS:-root1234}"
DB_NAME="${DB_NAME:-coupon_db}"

APP_PID=""

# ------------------------------------------------------------------ helpers

step()  { printf '\n=== %s\n' "$*"; }
info()  { printf '    %s\n' "$*"; }
die()   { printf '\n!!! FAILED: %s\n' "$*" >&2; exit 1; }

mysql_q() {
    docker exec "$MYSQL_C" mysql -u"$DB_USER" -p"$DB_PASS" "$DB_NAME" -Nse "$1" 2>/dev/null
}

redis_cli() {
    docker exec "$REDIS_C" redis-cli "$@"
}

kafka_sh() {
    MSYS_NO_PATHCONV=1 docker exec "$KAFKA_C" "$@"
}

cleanup() {
    if [[ -n "$APP_PID" && "$KEEP_APP" != "true" ]]; then
        info "stopping application (pid $APP_PID)"
        kill "$APP_PID" 2>/dev/null
        wait "$APP_PID" 2>/dev/null
    fi
}
trap cleanup EXIT

uuid4() {
    printf '%04x%04x-%04x-4%03x-%04x-%04x%04x%04x' \
        $((RANDOM%65536)) $((RANDOM%65536)) $((RANDOM%65536)) \
        $((RANDOM%4096))  $(((RANDOM%16384)+32768)) \
        $((RANDOM%65536)) $((RANDOM%65536)) $((RANDOM%65536))
}

# ------------------------------------------------------------------ round -> strategy

case "$ROUND" in
    V0) ISSUE_STRATEGY="NO_LOCK";          SAVE_STRATEGY="sync-db" ;;
    V1) ISSUE_STRATEGY="PESSIMISTIC_LOCK"; SAVE_STRATEGY="sync-db" ;;
    V2) ISSUE_STRATEGY="LUA_SCRIPT";       SAVE_STRATEGY="sync-db" ;;
    V3) ISSUE_STRATEGY="LUA_SCRIPT";       SAVE_STRATEGY="kafka"   ;;
    *)  die "unknown ROUND: $ROUND (use V0 | V1 | V2 | V3)" ;;
esac

USES_REDIS=false
[[ "$ISSUE_STRATEGY" == "LUA_SCRIPT" ]] && USES_REDIS=true

if [[ -z "$RUN_ID" ]]; then
    RUN_ID="SMOKE-${ROUND}-$(date +%Y%m%d-%H%M%S)"
fi

REPORT_FILE="${OUT_DIR}/${RUN_ID}.md"
RESPONSE_FILE="build/${RUN_ID}-responses.txt"

# ------------------------------------------------------------------ 0. build

step "0. build"
if [[ "$SKIP_BUILD" == "true" ]]; then
    info "SKIP_BUILD=true, using existing jar"
    [[ -f "$JAR" ]] || die "jar not found: $JAR"
else
    ./gradlew bootJar --quiet || die "bootJar failed"
fi
info "jar: $JAR"
info "git: $(git rev-parse --short HEAD 2>/dev/null || echo unknown)"

# ------------------------------------------------------------------ 1. start

step "1. start application  (round=$ROUND issue=$ISSUE_STRATEGY save=$SAVE_STRATEGY)"

if curl -s -o /dev/null -w '%{http_code}' "$BASE/actuator/health" | grep -q 200; then
    die "something is already listening on $BASE. stop it first — a stale process is the most common cause of a stale result."
fi

mkdir -p build "$OUT_DIR"
: > "$LOG"

COUPON_STRATEGY="$ISSUE_STRATEGY" COUPON_SAVE_STRATEGY="$SAVE_STRATEGY" \
    java -Duser.timezone=UTC -jar "$JAR" > "$LOG" 2>&1 &
APP_PID=$!
info "pid $APP_PID, log $LOG"

for _ in $(seq 1 60); do
    if curl -s "$BASE/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
        break
    fi
    kill -0 "$APP_PID" 2>/dev/null || die "application died during startup. see $LOG"
    sleep 1
done

curl -s "$BASE/actuator/health" | grep -q '"status":"UP"' \
    || die "application did not become healthy in 60s. see $LOG"

# ------------------------------------------------------------------ 2. assert strategy

step "2. assert the running strategy"

# The yml defaults are LUA_SCRIPT / sync-db. If the env var does not take
# effect the app starts anyway with a different strategy, and the round is
# recorded under a label it never actually ran. Check the log, not the intent.
grep -q "= ${ISSUE_STRATEGY} " "$LOG" \
    || die "issue strategy is not ${ISSUE_STRATEGY}. see $LOG"
info "issue strategy confirmed: $ISSUE_STRATEGY"

# ------------------------------------------------------------------ 3. reset round data

step "3. reset round data  (stock_id=$STOCK_ID -> $INITIAL_STOCK)"

mysql_q "DELETE h FROM coupon_history h
          JOIN coupons c ON c.coupon_id = h.coupon_id
         WHERE c.stock_id = ${STOCK_ID};
         DELETE FROM coupons WHERE stock_id = ${STOCK_ID};
         UPDATE campaign_stocks
            SET total_stock = ${INITIAL_STOCK}, remaining_stock = ${INITIAL_STOCK}
          WHERE stock_id = ${STOCK_ID};" || die "reset failed"

REMAINING=$(mysql_q "SELECT remaining_stock FROM campaign_stocks WHERE stock_id=${STOCK_ID};")
[[ "$REMAINING" == "$INITIAL_STOCK" ]] || die "remaining_stock is '$REMAINING', expected $INITIAL_STOCK"
info "remaining_stock = $REMAINING"

if [[ "$SAVE_STRATEGY" == "kafka" ]]; then
    # test-plan 9: DLT must start at 0. Assert-only at the end lets an
    # unrelated run poison the gate.
    if kafka_sh /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
         --list 2>/dev/null | grep -q '^coupon-issued.DLT$'; then
        kafka_sh /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
            --delete --topic coupon-issued.DLT >/dev/null 2>&1
        info "DLT topic purged"
    fi
fi

# verification_report / verification_violation are NOT cleared.
# run_id keeps rounds apart, and past rounds must stay comparable.

# ------------------------------------------------------------------ 4. warm redis

step "4. warm redis"

ROW=$(mysql_q "SET time_zone='+00:00';
               SELECT CAST(ROUND(UNIX_TIMESTAMP(c.open_at)*1000) AS UNSIGNED),
                      CAST(ROUND(UNIX_TIMESTAMP(c.expire_at)*1000) AS UNSIGNED)
                 FROM campaign_stocks cs
                 JOIN campaigns c ON c.campaign_id = cs.campaign_id
                WHERE cs.stock_id = ${STOCK_ID};")
read -r OPEN_MS EXPIRE_MS <<< "$ROW"
[[ -n "${OPEN_MS:-}" ]] || die "could not read campaign times for stock_id=$STOCK_ID"

# Every round needs these two keys: CouponServiceImpl reads the campaign
# window from redis before it picks a strategy, so even the DB-only rounds
# fail without them. See docs/round-results.md.
redis_cli MSET \
    "campaign:${CAMPAIGN_ID}:openAt"   "$OPEN_MS" \
    "campaign:${CAMPAIGN_ID}:expireAt" "$EXPIRE_MS" >/dev/null
info "campaign window cached"

if [[ "$USES_REDIS" == "true" ]]; then
    redis_cli DEL "issued:${CAMPAIGN_ID}" >/dev/null
    redis_cli MSET \
        "stock:${STOCK_ID}" "$INITIAL_STOCK" \
        "stockId:${CAMPAIGN_ID}:${ROUTE_ID}:${FARE_CLASS}" "$STOCK_ID" >/dev/null
    info "stock keys cached, issued set cleared"

    # REC-01 compares every pool in the DB, not just this round's pool.
    mysql_q "SELECT CONCAT('SET stock:', stock_id, ' ', remaining_stock)
               FROM campaign_stocks WHERE stock_id <> ${STOCK_ID};" \
        | docker exec -i "$REDIS_C" redis-cli >/dev/null
    info "other pools synced for REC-01"
fi

# ------------------------------------------------------------------ 5. issue

step "5. issue ${REQUESTS} concurrent requests"

: > "$RESPONSE_FILE"
ISSUE_PIDS=()
for i in $(seq 1 "$REQUESTS"); do
    (
        key=$(uuid4)
        body=$(printf '{"userId":%d,"campaignId":%d,"routeId":"%s","fareClass":"%s"}' \
            "$i" "$CAMPAIGN_ID" "$ROUTE_ID" "$FARE_CLASS")
        resp=$(curl -s -w '\n%{http_code}' -X POST "$BASE/api/coupons/issue" \
            -H 'Content-Type: application/json' \
            -H "Idempotency-Key: $key" \
            -d "$body")
        code=$(printf '%s' "$resp" | tail -n1)
        payload=$(printf '%s' "$resp" | sed '$d')
        reason=$(printf '%s' "$payload" | grep -o '"errorCode":"[A-Z_]*"' | head -1 | cut -d'"' -f4)
        echo "${code} ${reason:-OK}" >> "$RESPONSE_FILE"
    ) &
    ISSUE_PIDS+=("$!")
done

# 인자 없는 wait 는 백그라운드로 띄운 애플리케이션까지 기다린다.
# 발급 요청 PID 만 명시적으로 기다린다.
for p in "${ISSUE_PIDS[@]}"; do
    wait "$p" 2>/dev/null
done

sort "$RESPONSE_FILE" | uniq -c | sort -rn | sed 's/^/    /'

TOTAL=$(wc -l < "$RESPONSE_FILE" | tr -d ' ')
[[ "$TOTAL" == "$REQUESTS" ]] || die "only $TOTAL responses recorded, expected $REQUESTS"

SUCCESS=$(grep -c '^200 ' "$RESPONSE_FILE")
SERVER_ERR=$(grep -c '^5' "$RESPONSE_FILE")
info "success=$SUCCESS  5xx=$SERVER_ERR"

# 발급이 0 건이면 아무 데이터도 만들지 않은 회차다.
# 그 위에서 나온 "위반 0 건"은 아무것도 검사하지 않았다는 뜻이 된다.
[[ "$SUCCESS" -gt 0 ]] || die "no coupon was issued. an empty round cannot pass."

# V0 는 동시성 제어 부재를 재현하는 것이 목적이라 제외한다 (test-plan 5.4).
if [[ "$ROUND" != "V0" ]]; then
    [[ "$SERVER_ERR" == "0" ]] || die "$SERVER_ERR server errors. test-plan 6.6 requires zero."
fi

# ------------------------------------------------------------------ 6. wait for kafka

if [[ "$SAVE_STRATEGY" == "kafka" ]]; then
    step "6. wait for consumer lag 0"
    SETTLED=false
    for _ in $(seq 1 60); do
        LAG_SUM=$(kafka_sh /opt/kafka/bin/kafka-consumer-groups.sh \
                    --bootstrap-server localhost:9092 \
                    --group coupon-service --describe 2>/dev/null \
                  | awk '$1=="coupon-service" && $6 ~ /^[0-9]+$/ {s+=$6} END {print s+0}')
        if [[ "$LAG_SUM" == "0" ]]; then
            SETTLED=true
            break
        fi
        info "lag=$LAG_SUM"
        sleep 2
    done
    [[ "$SETTLED" == "true" ]] || die "consumer lag did not reach 0 in 120s"
    info "lag 0"

    DLT=$(kafka_sh /opt/kafka/bin/kafka-topics.sh \
            --bootstrap-server localhost:9092 --list 2>/dev/null \
          | grep -c '^coupon-issued.DLT$')
    if [[ "$DLT" != "0" ]]; then
        COUNT=$(kafka_sh /opt/kafka/bin/kafka-get-offsets.sh \
                  --bootstrap-server localhost:9092 --topic coupon-issued.DLT 2>/dev/null \
                | awk -F: '{s+=$3} END {print s+0}')
        [[ "$COUNT" == "0" ]] || die "DLT holds $COUNT messages"
    fi
    info "DLT 0"
else
    step "6. wait for consumer lag 0  (skipped, save strategy is $SAVE_STRATEGY)"
fi

# ------------------------------------------------------------------ 7. verify

step "7. verification batch  (runId=$RUN_ID round=$ROUND)"

LAUNCH=$(curl -s -X POST \
    "$BASE/api/admin/batch/verification?runId=${RUN_ID}&round=${ROUND}&failOnViolation=${FAIL_ON_VIOLATION}")
EXEC_ID=$(printf '%s' "$LAUNCH" | grep -o '"jobExecutionId":[0-9]*' | cut -d: -f2)
[[ -n "$EXEC_ID" ]] || die "could not launch verification: $LAUNCH"

await_execution() {
    local id="$1" label="$2" status=""
    for _ in $(seq 1 120); do
        status=$(curl -s "$BASE/api/admin/batch/executions/${id}" \
                 | grep -o '"status":"[A-Z]*"' | head -1 | cut -d'"' -f4)
        case "$status" in
            COMPLETED|FAILED|STOPPED|ABANDONED) break ;;
        esac
        sleep 1
    done
    printf '%s' "$status"
}

STATUS=$(await_execution "$EXEC_ID" verification)
info "execution $EXEC_ID -> $STATUS"
[[ "$STATUS" == "COMPLETED" ]] || die "verification ended as $STATUS. see $BASE/api/admin/batch/verification/runs/${RUN_ID}/violations"

# ------------------------------------------------------------------ 8. reconcile

step "8. reconciliation batch  (same runId, so both land in one report)"

LAUNCH=$(curl -s -X POST "$BASE/api/admin/batch/reconcile?runId=${RUN_ID}")
EXEC_ID=$(printf '%s' "$LAUNCH" | grep -o '"jobExecutionId":[0-9]*' | cut -d: -f2)
[[ -n "$EXEC_ID" ]] || die "could not launch reconcile: $LAUNCH"

STATUS=$(await_execution "$EXEC_ID" reconcile)
info "execution $EXEC_ID -> $STATUS"
[[ "$STATUS" == "COMPLETED" ]] || die "reconciliation ended as $STATUS"

# ------------------------------------------------------------------ 9. report

step "9. report"

curl -s "$BASE/api/admin/batch/verification/runs/${RUN_ID}/report" -o "$REPORT_FILE"
[[ -s "$REPORT_FILE" ]] || die "report is empty"

RULE_ROWS=$(grep -c '^| `' "$REPORT_FILE")
[[ "$RULE_ROWS" -ge 15 ]] || die "report holds only $RULE_ROWS rule rows, expected 15 or more"

# A rule can pass because it was never run. The report separates the two,
# so refuse a run that left anything unexecuted.
if grep -q '미실행 [1-9]' "$REPORT_FILE"; then
    die "some rules did not run. see $REPORT_FILE"
fi

info "$REPORT_FILE  ($RULE_ROWS rules)"
printf '\n'
sed -n '1,12p' "$REPORT_FILE"

VIOLATIONS=$(grep -o '^| 총 위반 | [0-9]*' "$REPORT_FILE" | grep -o '[0-9]*$')
[[ "$VIOLATIONS" == "0" ]] || die "report holds $VIOLATIONS violations. see $REPORT_FILE"

# 리포트가 스스로 내린 판정을 그대로 게이트로 쓴다. 위반 수만 보면
# 무효(시계 어긋남)나 불완전(미실행)처럼 위반 0 건인 실패를 놓친다.
grep -q '^| 판정 | \*\*통과\*\* |' "$REPORT_FILE" \
    || die "report verdict is not 통과. see $REPORT_FILE"

step "PASSED  round=$ROUND  runId=$RUN_ID"