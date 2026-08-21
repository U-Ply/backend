#!/usr/bin/env bash
#
# End-to-end acceptance run for one round.
#
#   ROUND=V1 ./scripts/test/acceptance.sh
#   ROUND=V3 REQUESTS=200 ./scripts/test/acceptance.sh
#   TRANSITIONS=false ROUND=V1 ./scripts/test/acceptance.sh   # skip 5b/5c
#
# The script owns the whole chain so no step can be skipped by accident:
#
#   build -> start -> assert strategy -> reset -> warm redis -> issue
#         -> use/cancel -> expire -> wait kafka lag 0
#         -> verify -> reconcile -> report -> stop
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

# 상태 전이 회차 (5b/5c).
# 발급만 하고 끝나면 쿠폰이 전부 ISSUED 로 남아, INV-05/06/07 의 사용·취소·만료 절이
# 실제 발급 경로가 만든 데이터로는 한 번도 검사되지 않는다. 더미데이터의 종료 상태 행은
# 배치가 만든 것이 아니라 직접 INSERT 한 값이라 이를 대신하지 못한다.
TRANSITIONS="${TRANSITIONS:-true}"
USE_COUNT="${USE_COUNT:-10}"        # ISSUED -> USED
CANCEL_COUNT="${CANCEL_COUNT:-5}"   # USED -> CANCELLED (사용한 것 중 일부)

# 만료 전용 캠페인.
# 캠페인 31 의 expire_at 을 과거로 옮기면 INV-11(issued_at >= expire_at)이
# 이미 발급된 30건 전부에 걸린다. 그래서 만료는 별도 캠페인에서 태운다.
EXPIRY_CAMPAIGN_ID="${EXPIRY_CAMPAIGN_ID:-32}"
EXPIRY_CAMPAIGN_NAME="${EXPIRY_CAMPAIGN_NAME:-SMOKE-EXPIRY}"
EXPIRY_STOCK_ID="${EXPIRY_STOCK_ID:-302}"
EXPIRY_ROUTE_ID="${EXPIRY_ROUTE_ID:-SMOKE}"
EXPIRY_FARE_CLASS="${EXPIRY_FARE_CLASS:-SMOKE}"
EXPIRY_STOCK="${EXPIRY_STOCK:-10}"
EXPIRY_REQUESTS="${EXPIRY_REQUESTS:-15}"
EXPIRY_WINDOW_SEC="${EXPIRY_WINDOW_SEC:-30}"
EXPIRY_USER_BASE="${EXPIRY_USER_BASE:-0}"

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

# 병렬 서브셸이 같은 RANDOM 상태를 물려받아 키가 겹치는 일이 있었다.
# 마지막 12자리에 회차 고유값 + 요청 번호를 박아 충돌을 없앤다.
RUN_SALT=$(( $(date +%s) % 1000000 ))

uuid4() {
    local unique="${1:-$((RANDOM % 65536))}"
    printf '%04x%04x-%04x-4%03x-%04x-%012x' \
        $((RANDOM%65536)) $((RANDOM%65536)) $((RANDOM%65536)) \
        $((RANDOM%4096))  $(((RANDOM%16384)+32768)) \
        $(( RUN_SALT * 1000 + unique ))
}

# 7단계 안에 있던 것을 helpers 로 올렸다. 5c 의 만료 배치가 7단계보다 먼저 부른다.
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
COUPON_FILE="build/${RUN_ID}-coupons.txt"
EXPIRY_RESPONSE_FILE="build/${RUN_ID}-expiry-responses.txt"

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
         WHERE c.stock_id IN (${STOCK_ID}, ${EXPIRY_STOCK_ID});
         DELETE FROM coupons WHERE stock_id IN (${STOCK_ID}, ${EXPIRY_STOCK_ID});
         UPDATE campaign_stocks
            SET total_stock = ${INITIAL_STOCK}, remaining_stock = ${INITIAL_STOCK}
          WHERE stock_id = ${STOCK_ID};" || die "reset failed"

REMAINING=$(mysql_q "SELECT remaining_stock FROM campaign_stocks WHERE stock_id=${STOCK_ID};")
[[ "$REMAINING" == "$INITIAL_STOCK" ]] || die "remaining_stock is '$REMAINING', expected $INITIAL_STOCK"
info "remaining_stock = $REMAINING"

LEFTOVER=$(mysql_q "SELECT COUNT(*) FROM coupons WHERE stock_id IN (${STOCK_ID}, ${EXPIRY_STOCK_ID});")
[[ "$LEFTOVER" == "0" ]] || die "reset left $LEFTOVER coupons behind"

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
: > "$COUPON_FILE"
ISSUE_PIDS=()
for i in $(seq 1 "$REQUESTS"); do
    (
        key=$(uuid4 "$i")
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

        # couponId 는 TSID 문자열로 온다: "couponId":"878551090355410649"
        # 상태 전이 회차가 이 값을 써야 하므로 버리지 않는다.
        if [[ "$code" == "200" ]]; then
            cid=$(printf '%s' "$payload" | grep -o '"couponId":"[0-9]*"' | head -1 | cut -d'"' -f4)
            [[ -n "$cid" ]] && echo "$cid" >> "$COUPON_FILE"
        fi
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
CAPTURED=$(wc -l < "$COUPON_FILE" | tr -d ' ')
info "success=$SUCCESS  5xx=$SERVER_ERR  couponIds=$CAPTURED"

# 발급이 0 건이면 아무 데이터도 만들지 않은 회차다.
# 그 위에서 나온 "위반 0 건"은 아무것도 검사하지 않았다는 뜻이 된다.
[[ "$SUCCESS" -gt 0 ]] || die "no coupon was issued. an empty round cannot pass."

# 성공 응답 수와 확보한 couponId 수가 다르면 상태 전이 회차의 대상이 불완전하다.
[[ "$CAPTURED" == "$SUCCESS" ]] \
    || die "captured $CAPTURED couponIds but $SUCCESS successes — response shape changed?"

# V0 는 동시성 제어 부재를 재현하는 것이 목적이라 제외한다 (test-plan 5.4).
if [[ "$ROUND" != "V0" ]]; then
    [[ "$SERVER_ERR" == "0" ]] || die "$SERVER_ERR server errors. test-plan 6.6 requires zero."
fi

# ------------------------------------------------------------------ 5b. use / cancel

if [[ "$TRANSITIONS" == "true" ]]; then
    step "5b. state transitions  (use ${USE_COUNT}, then cancel ${CANCEL_COUNT} of them)"

    # V3 는 컨슈머가 DB 에 쓰기 전까지 쿠폰 행이 없다. 상태 전이는 DB 행을 전제로 하므로
    # 여기서 먼저 정착을 기다린다. 6단계의 lag 게이트는 그대로 둔다.
    if [[ "$SAVE_STRATEGY" == "kafka" ]]; then
        for _ in $(seq 1 30); do
            DB_NOW=$(mysql_q "SELECT COUNT(*) FROM coupons WHERE stock_id=${STOCK_ID};")
            [[ "$DB_NOW" == "$SUCCESS" ]] && break
            sleep 1
        done
    fi

    DB_ISSUED=$(mysql_q "SELECT COUNT(*) FROM coupons WHERE stock_id=${STOCK_ID};")
    [[ "$DB_ISSUED" == "$SUCCESS" ]] \
        || die "db holds $DB_ISSUED coupons but $SUCCESS were issued"

    mapfile -t COUPON_IDS < "$COUPON_FILE"
    [[ "${#COUPON_IDS[@]}" -ge "$USE_COUNT" ]] \
        || die "USE_COUNT=$USE_COUNT but only ${#COUPON_IDS[@]} coupons available"
    [[ "$CANCEL_COUNT" -le "$USE_COUNT" ]] \
        || die "CANCEL_COUNT must be <= USE_COUNT"

    for (( i = 0; i < USE_COUNT; i++ )); do
        id="${COUPON_IDS[$i]}"
                code=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
            "$BASE/api/coupons/${id}/use" \
            -H "Idempotency-Key: $(uuid4 $(( 200 + i )))")
        [[ "$code" == "200" ]] || die "use coupon $id returned HTTP $code"
    done
    info "used $USE_COUNT coupons"

    # 사용한 것 중 일부를 취소한다. USED -> CANCELLED 는 INV-07 의
    # "취소 시각과 만료 시각이 함께 있는" 절을 처음으로 태우는 경로다.
    for (( i = 0; i < CANCEL_COUNT; i++ )); do
        id="${COUPON_IDS[$i]}"
               code=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
            "$BASE/api/coupons/${id}/cancel" \
            -H "Idempotency-Key: $(uuid4 $(( 400 + i )))")
        [[ "$code" == "200" ]] || die "cancel coupon $id returned HTTP $code"
    done
    info "cancelled $CANCEL_COUNT of the used coupons"

    EXPECT_USED=$(( USE_COUNT - CANCEL_COUNT ))
    EXPECT_ISSUED=$(( SUCCESS - USE_COUNT ))
    ACT_USED=$(mysql_q      "SELECT COUNT(*) FROM coupons WHERE stock_id=${STOCK_ID} AND status='USED';")
    ACT_CANCELLED=$(mysql_q "SELECT COUNT(*) FROM coupons WHERE stock_id=${STOCK_ID} AND status='CANCELLED';")
    ACT_ISSUED=$(mysql_q    "SELECT COUNT(*) FROM coupons WHERE stock_id=${STOCK_ID} AND status='ISSUED';")
    info "stock $STOCK_ID -> ISSUED=$ACT_ISSUED USED=$ACT_USED CANCELLED=$ACT_CANCELLED"

    [[ "$ACT_USED" == "$EXPECT_USED" ]]           || die "expected USED=$EXPECT_USED, got $ACT_USED"
    [[ "$ACT_CANCELLED" == "$CANCEL_COUNT" ]]     || die "expected CANCELLED=$CANCEL_COUNT, got $ACT_CANCELLED"
    [[ "$ACT_ISSUED" == "$EXPECT_ISSUED" ]]       || die "expected ISSUED=$EXPECT_ISSUED, got $ACT_ISSUED"

    # 발급된 재고는 상태와 무관하게 영구 소진한다 (test-plan 2.8).
    # 사용·취소가 재고를 되돌리면 여기서 잡힌다.
    AFTER_STOCK=$(mysql_q "SELECT remaining_stock FROM campaign_stocks WHERE stock_id=${STOCK_ID};")
    EXPECT_STOCK=$(( INITIAL_STOCK - SUCCESS ))
    [[ "$AFTER_STOCK" == "$EXPECT_STOCK" ]] \
        || die "remaining_stock is $AFTER_STOCK, expected $EXPECT_STOCK — state change must not restore stock"
    info "remaining_stock still $AFTER_STOCK (state changes do not restore stock)"
else
    step "5b. state transitions  (skipped, TRANSITIONS=$TRANSITIONS)"
fi

# ------------------------------------------------------------------ 5c. expiration batch

if [[ "$TRANSITIONS" == "true" ]]; then
    step "5c. expiration batch  (campaign $EXPIRY_CAMPAIGN_ID, window ${EXPIRY_WINDOW_SEC}s)"

    # 만료 전용 캠페인을 따로 두는 이유:
    # 캠페인 31 의 expire_at 을 과거로 옮기면 INV-11(issued_at >= expire_at)이
    # 위에서 발급한 30건 전부에 걸린다.
    mysql_q "SET time_zone='+00:00';
             INSERT INTO campaigns (campaign_id, name, open_at, expire_at)
             VALUES (${EXPIRY_CAMPAIGN_ID}, '${EXPIRY_CAMPAIGN_NAME}',
                     NOW(3), DATE_ADD(NOW(3), INTERVAL ${EXPIRY_WINDOW_SEC} SECOND))
             ON DUPLICATE KEY UPDATE
                     name = VALUES(name),
                     open_at = VALUES(open_at),
                     expire_at = VALUES(expire_at);" || die "expiry campaign upsert failed"

    mysql_q "INSERT INTO campaign_stocks
               (stock_id, campaign_id, route_id, fare_class, total_stock, remaining_stock)
             VALUES (${EXPIRY_STOCK_ID}, ${EXPIRY_CAMPAIGN_ID},
                     '${EXPIRY_ROUTE_ID}', '${EXPIRY_FARE_CLASS}',
                     ${EXPIRY_STOCK}, ${EXPIRY_STOCK})
             ON DUPLICATE KEY UPDATE
                     campaign_id = VALUES(campaign_id),
                     route_id = VALUES(route_id),
                     fare_class = VALUES(fare_class),
                     total_stock = VALUES(total_stock),
                     remaining_stock = VALUES(remaining_stock);" || die "expiry stock upsert failed"

    EXP_ROW=$(mysql_q "SET time_zone='+00:00';
                       SELECT CAST(ROUND(UNIX_TIMESTAMP(open_at)*1000) AS UNSIGNED),
                              CAST(ROUND(UNIX_TIMESTAMP(expire_at)*1000) AS UNSIGNED)
                         FROM campaigns WHERE campaign_id = ${EXPIRY_CAMPAIGN_ID};")
    read -r EXP_OPEN_MS EXP_EXPIRE_MS <<< "$EXP_ROW"
    [[ -n "${EXP_OPEN_MS:-}" ]] || die "could not read expiry campaign times"

    # 4단계와 같은 형태로 웜업한다. 두 곳이 어긋나면 만료 회차만 조용히 실패한다.
    redis_cli MSET \
        "campaign:${EXPIRY_CAMPAIGN_ID}:openAt"   "$EXP_OPEN_MS" \
        "campaign:${EXPIRY_CAMPAIGN_ID}:expireAt" "$EXP_EXPIRE_MS" >/dev/null

    if [[ "$USES_REDIS" == "true" ]]; then
        redis_cli DEL "issued:${EXPIRY_CAMPAIGN_ID}" >/dev/null
        redis_cli MSET \
            "stock:${EXPIRY_STOCK_ID}" "$EXPIRY_STOCK" \
            "stockId:${EXPIRY_CAMPAIGN_ID}:${EXPIRY_ROUTE_ID}:${EXPIRY_FARE_CLASS}" "$EXPIRY_STOCK_ID" \
            >/dev/null
    fi
    info "expiry campaign ready (stock $EXPIRY_STOCK, window ${EXPIRY_WINDOW_SEC}s)"
    # coupons.user_id 에 users FK 가 걸려 있다. 존재하지 않는 사용자로 발급하면
    # 전부 500 이 되는데, 응답만 보면 원인이 FK 인지 재고인지 알 수 없다.
    USER_OK=$(mysql_q "SELECT COUNT(*) FROM users
                        WHERE user_id BETWEEN $(( EXPIRY_USER_BASE + 1 )) AND $(( EXPIRY_USER_BASE + EXPIRY_REQUESTS ));")
    [[ "$USER_OK" == "$EXPIRY_REQUESTS" ]] \
        || die "expiry round needs users $(( EXPIRY_USER_BASE + 1 ))..$(( EXPIRY_USER_BASE + EXPIRY_REQUESTS )) but only $USER_OK exist"
    : > "$EXPIRY_RESPONSE_FILE"
    EXPIRY_PIDS=()
    for i in $(seq 1 "$EXPIRY_REQUESTS"); do
        (
            key=$(uuid4 $(( 600 + i )))
            uid=$(( EXPIRY_USER_BASE + i ))
            body=$(printf '{"userId":%d,"campaignId":%d,"routeId":"%s","fareClass":"%s"}' \
                "$uid" "$EXPIRY_CAMPAIGN_ID" "$EXPIRY_ROUTE_ID" "$EXPIRY_FARE_CLASS")
            resp=$(curl -s -w '\n%{http_code}' -X POST "$BASE/api/coupons/issue" \
                -H 'Content-Type: application/json' \
                -H "Idempotency-Key: $key" \
                -d "$body")
            printf '%s\n' "$(printf '%s' "$resp" | tail -n1)" >> "$EXPIRY_RESPONSE_FILE"
        ) &
        EXPIRY_PIDS+=("$!")
    done
    for p in "${EXPIRY_PIDS[@]}"; do
        wait "$p" 2>/dev/null
    done

    EXP_SUCCESS=$(grep -c '^200$' "$EXPIRY_RESPONSE_FILE")
    EXP_5XX=$(grep -c '^5' "$EXPIRY_RESPONSE_FILE")
    info "expiry campaign issue: success=$EXP_SUCCESS  5xx=$EXP_5XX"
    [[ "$EXP_5XX" == "0" ]]     || die "$EXP_5XX server errors on the expiry campaign"
    [[ "$EXP_SUCCESS" -gt 0 ]]  || die "expiry campaign issued nothing — cannot test expiration"

    if [[ "$SAVE_STRATEGY" == "kafka" ]]; then
        for _ in $(seq 1 30); do
            EXP_DB=$(mysql_q "SELECT COUNT(*) FROM coupons WHERE stock_id=${EXPIRY_STOCK_ID};")
            [[ "$EXP_DB" == "$EXP_SUCCESS" ]] && break
            sleep 1
        done
    fi
    EXP_IN_DB=$(mysql_q "SELECT COUNT(*) FROM coupons WHERE stock_id=${EXPIRY_STOCK_ID};")
    [[ "$EXP_IN_DB" == "$EXP_SUCCESS" ]] \
        || die "expiry campaign: db holds $EXP_IN_DB coupons but $EXP_SUCCESS were issued"

    # 만료 창이 닫히기 전에 배치를 돌리면 expired_at < expire_at 이 되어
    # INV-06(시각 순서)이 위반으로 잡는다. 창이 닫힐 때까지 기다린다.
    WAIT_SEC=$(( EXPIRY_WINDOW_SEC + 5 ))
    info "waiting ${WAIT_SEC}s for the expiry window to close (INV-06 forbids expired_at < expire_at)"
    sleep "$WAIT_SEC"

    ELIGIBLE=$(mysql_q "SELECT COUNT(*) FROM coupons
                         WHERE status='ISSUED' AND expire_at <= NOW(3);")
    info "coupons eligible for expiration across the whole table: $ELIGIBLE"

    LAUNCH=$(curl -s -X POST "$BASE/api/admin/batch/expiration?runId=${RUN_ID}-EXPIRE")
    EXEC_ID=$(printf '%s' "$LAUNCH" | grep -o '"jobExecutionId":[0-9]*' | cut -d: -f2)
    [[ -n "$EXEC_ID" ]] || die "could not launch expiration: $LAUNCH"

    STATUS=$(await_execution "$EXEC_ID" expiration)
    info "execution $EXEC_ID -> $STATUS"
    [[ "$STATUS" == "COMPLETED" ]] || die "expiration ended as $STATUS"

    EXPIRED=$(mysql_q     "SELECT COUNT(*) FROM coupons WHERE stock_id=${EXPIRY_STOCK_ID} AND status='EXPIRED';")
    NOT_EXPIRED=$(mysql_q "SELECT COUNT(*) FROM coupons WHERE stock_id=${EXPIRY_STOCK_ID} AND status<>'EXPIRED';")
    info "expiry stock $EXPIRY_STOCK_ID -> EXPIRED=$EXPIRED, other=$NOT_EXPIRED"
    [[ "$EXPIRED" == "$EXP_SUCCESS" ]] || die "expected $EXP_SUCCESS expired, got $EXPIRED"
    [[ "$NOT_EXPIRED" == "0" ]]        || die "$NOT_EXPIRED coupons survived the expiration batch"

    # 만료 배치가 회차 캠페인까지 건드리면 5b 의 분포가 무너진다.
    # 캠페인 31 은 만료가 한참 남았으므로 손대지 않아야 한다.
    STILL=$(mysql_q "SELECT COUNT(*) FROM coupons WHERE stock_id=${STOCK_ID} AND status='EXPIRED';")
    [[ "$STILL" == "0" ]] \
        || die "$STILL coupons of the main round were expired — the batch touched a live campaign"

    # 만료 이력이 실제로 남았는지 본다. 상태만 바꾸고 이력을 남기지 않으면
    # INV-04(현재 상태 = 최종 이력)가 잡아야 하지만, 여기서 먼저 확인한다.
    EXP_HISTORY=$(mysql_q "SELECT COUNT(*) FROM coupon_history h
                             JOIN coupons c ON c.coupon_id = h.coupon_id
                            WHERE c.stock_id = ${EXPIRY_STOCK_ID} AND h.to_status = 'EXPIRED';")
    [[ "$EXP_HISTORY" == "$EXP_SUCCESS" ]] \
        || die "expiration wrote $EXP_HISTORY history rows, expected $EXP_SUCCESS"
    info "expiration history rows: $EXP_HISTORY"
else
    step "5c. expiration batch  (skipped, TRANSITIONS=$TRANSITIONS)"
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

# 이번 회차가 만든 상태 분포. "위반 0 건" 이 어떤 데이터 위에서 나온 값인지 남긴다.
if [[ "$TRANSITIONS" == "true" ]]; then
    step "final state distribution"
    mysql_q "SELECT stock_id, status, COUNT(*)
               FROM coupons
              WHERE stock_id IN (${STOCK_ID}, ${EXPIRY_STOCK_ID})
              GROUP BY stock_id, status
              ORDER BY stock_id, status;" | sed 's/^/    /'
    HIST=$(mysql_q "SELECT COUNT(*) FROM coupon_history h
                      JOIN coupons c ON c.coupon_id = h.coupon_id
                     WHERE c.stock_id IN (${STOCK_ID}, ${EXPIRY_STOCK_ID});")
    info "history rows for this round: $HIST"
fi

step "PASSED  round=$ROUND  runId=$RUN_ID"