#!/usr/bin/env bash

#
# End-to-end acceptance run for one round.
#
#   ROUND=V1 ALLOW_DESTRUCTIVE_ACCEPTANCE=true ./scripts/test/acceptance.sh
#   ROUND=V3 REQUESTS=200 ALLOW_DESTRUCTIVE_ACCEPTANCE=true ./scripts/test/acceptance.sh
#
# Diagnostic only:
#   TRANSITIONS=false ALLOW_DESTRUCTIVE_ACCEPTANCE=true \
#     ./scripts/test/acceptance.sh
#
# Full acceptance flow:
#
#   build
#     -> start
#     -> assert strategy
#     -> reset
#     -> warm redis
#     -> issue
#     -> use/cancel
#     -> expire
#     -> wait kafka lag 0
#     -> verify
#     -> reconcile
#     -> report
#     -> stop
#
# A green run means every required gate actually ran.
#

set -uo pipefail

# ------------------------------------------------------------------
# config
# ------------------------------------------------------------------

ROUND="${ROUND:-V1}"
RUN_ID="${RUN_ID:-}"

CAMPAIGN_ID="${CAMPAIGN_ID:-31}"
STOCK_ID="${STOCK_ID:-301}"
ROUTE_ID="${ROUTE_ID:-JEJU}"
FARE_CLASS="${FARE_CLASS:-ECONOMY}"

INITIAL_STOCK="${INITIAL_STOCK:-30}"
REQUESTS="${REQUESTS:-100}"

# Full acceptance requires this to be true.
TRANSITIONS="${TRANSITIONS:-true}"

USE_COUNT="${USE_COUNT:-10}"
CANCEL_COUNT="${CANCEL_COUNT:-5}"

# Separate campaign used exclusively by the expiration test.
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

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-3306}"

DB_USER="${DB_USER:-root}"
DB_PASS="${DB_PASS:-root1234}"

SOURCE_DB_NAME="${SOURCE_DB_NAME:-coupon_db}"
DB_NAME="${DB_NAME:-coupon_acceptance_db}"

REDIS_DB="${REDIS_DB:-15}"

# This script intentionally mutates DB / Redis / Kafka state.
ALLOW_DESTRUCTIVE_ACCEPTANCE="${ALLOW_DESTRUCTIVE_ACCEPTANCE:-false}"

DLT_TOPIC="${DLT_TOPIC:-coupon-issued.DLT}"
DLT_BASELINE=0

DB_CLONED=false
APP_PID=""

REPORT_FILE=""
RESPONSE_FILE=""
COUPON_FILE=""
EXPIRY_RESPONSE_FILE=""

# ------------------------------------------------------------------
# helpers
# ------------------------------------------------------------------

step() {
    printf '\n=== %s\n' "$*"
}

info() {
    printf '    %s\n' "$*"
}

die() {
    printf '\n!!! FAILED: %s\n' "$*" >&2
    exit 1
}

mysql_q() {
    docker exec "$MYSQL_C" \
        mysql \
        -u"$DB_USER" \
        -p"$DB_PASS" \
        "$DB_NAME" \
        -Nse "$1" 2>/dev/null
}

redis_cli() {
    docker exec "$REDIS_C" \
        redis-cli \
        -n "$REDIS_DB" \
        "$@"
}

kafka_sh() {
    MSYS_NO_PATHCONV=1 docker exec "$KAFKA_C" "$@"
}

require_command() {
    command -v "$1" >/dev/null 2>&1 ||
        die "required command not found: $1"
}

require_container() {
    docker inspect "$1" >/dev/null 2>&1 ||
        die "docker container not found: $1"
}

wait_http_200() {
    local url="$1"
    local timeout="${2:-60}"
    local i

    for i in $(seq 1 "$timeout"); do
        if curl -fsS -o /dev/null "$url" 2>/dev/null; then
            return 0
        fi
        sleep 1
    done

    return 1
}

json_field() {
    local field="$1"
    local body="$2"

    printf '%s' "$body" |
        grep -o "\"${field}\":[^,}]*" |
        head -1 |
        cut -d: -f2- |
        tr -d '"'
}

await_execution() {
    local id="$1"
    local label="$2"
    local status=""
    local body=""
    local i

    for i in $(seq 1 120); do
        body=$(curl -fsS \
            "$BASE/api/admin/batch/executions/${id}" \
            2>/dev/null) || body=""

        status=$(json_field "status" "$body")

        case "$status" in
            COMPLETED|FAILED|STOPPED|ABANDONED)
                printf '%s' "$status"
                return 0
                ;;
        esac

        sleep 1
    done

    info "$label execution $id timed out"
    printf '%s' "$status"
}

uuid4() {
    if command -v uuidgen >/dev/null 2>&1; then
        uuidgen | tr '[:upper:]' '[:lower:]'
        return
    fi

    if [[ -r /proc/sys/kernel/random/uuid ]]; then
        cat /proc/sys/kernel/random/uuid
        return
    fi

    if command -v openssl >/dev/null 2>&1; then
        local hex
        hex="$(openssl rand -hex 16)"
        printf '%s-%s-4%s-8%s-%s\n' \
            "${hex:0:8}" \
            "${hex:8:4}" \
            "${hex:12:3}" \
            "${hex:15:3}" \
            "${hex:18:12}"
        return
    fi

    die "uuidgen, /proc random uuid, or openssl is required"
}

cleanup() {
    if [[ -n "$APP_PID" && "$KEEP_APP" != "true" ]]; then
        info "stopping application (pid $APP_PID)"
        kill "$APP_PID" 2>/dev/null || true
        wait "$APP_PID" 2>/dev/null || true
    fi
}

trap cleanup EXIT

# ------------------------------------------------------------------
# destructive acceptance
# ------------------------------------------------------------------

assert_destructive_allowed() {
    [[ "$ALLOW_DESTRUCTIVE_ACCEPTANCE" == "true" ]] ||
        die "set ALLOW_DESTRUCTIVE_ACCEPTANCE=true to run acceptance"

    [[ "$DB_NAME" != "$SOURCE_DB_NAME" ]] ||
        die "acceptance DB must be isolated from SOURCE_DB_NAME"

    [[ "$DB_NAME" == coupon_acceptance_db ||
       "$DB_NAME" == coupon_acceptance_* ]] ||
        die \
            "refusing non-isolated DB_NAME=$DB_NAME; use coupon_acceptance_db or coupon_acceptance_*"
}

prepare_acceptance_db() {
    [[ "$DB_NAME" != "$SOURCE_DB_NAME" ]] ||
        die "DB_NAME must not equal SOURCE_DB_NAME"

    [[ "$DB_NAME" == coupon_acceptance_db ||
       "$DB_NAME" == coupon_acceptance_* ]] ||
        die \
            "refusing non-isolated DB_NAME=$DB_NAME; use coupon_acceptance_db or coupon_acceptance_*"

    info "cloning source DB $SOURCE_DB_NAME -> isolated DB $DB_NAME"

    docker exec "$MYSQL_C" \
        mysql \
        -u"$DB_USER" \
        -p"$DB_PASS" \
        -Nse \
        "DROP DATABASE IF EXISTS \`$DB_NAME\`;
         CREATE DATABASE \`$DB_NAME\`;" ||
        die "could not create isolated acceptance database $DB_NAME"

    docker exec "$MYSQL_C" \
        mysqldump \
        -u"$DB_USER" \
        -p"$DB_PASS" \
        --single-transaction \
        --routines \
        --triggers \
        --events \
        --no-tablespaces \
        "$SOURCE_DB_NAME" |
    docker exec -i "$MYSQL_C" \
        mysql \
        -u"$DB_USER" \
        -p"$DB_PASS" \
        "$DB_NAME" ||
        die "could not clone $SOURCE_DB_NAME into $DB_NAME"

    DB_CLONED=true

    # Remove all lifecycle data from the isolated clone.
    mysql_q "
        DELETE FROM coupon_history;
        DELETE FROM coupons;
    " || die "could not clear coupon lifecycle data"

    # Normalize all stock counters after removing coupons.
    mysql_q "
        UPDATE campaign_stocks
           SET remaining_stock = total_stock;
    " || die "could not normalize campaign_stocks"

    # Ensure verification_report has round/status columns.
    local has_round
    local has_status

    has_round="$(
        mysql_q "
            SELECT COUNT(*)
              FROM information_schema.columns
             WHERE table_schema='${DB_NAME}'
               AND table_name='verification_report'
               AND column_name='round';
        "
    )"

    has_status="$(
        mysql_q "
            SELECT COUNT(*)
              FROM information_schema.columns
             WHERE table_schema='${DB_NAME}'
               AND table_name='verification_report'
               AND column_name='status';
        "
    )"

    if [[ "$has_round" == "0" ]]; then
        mysql_q "
            ALTER TABLE verification_report
            ADD COLUMN round VARCHAR(10) NULL
            COMMENT '검증 대상 회차 V0~V3'
            AFTER run_id;
        " || die "verification_report.round migration failed"
    fi

    if [[ "$has_status" == "0" ]]; then
        mysql_q "
            ALTER TABLE verification_report
            ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'CHECKED'
            COMMENT 'CHECKED | NOT_APPLICABLE | SKIPPED'
            AFTER round;
        " || die "verification_report.status migration failed"
    fi

    info "isolated DB ready: $DB_NAME"
}

print_destructive_summary() {
    local coupon_count
    local history_count
    local main_stock
    local expiry_stock_count
    local dlt_count

    coupon_count="$(
        mysql_q "
            SELECT COUNT(*)
              FROM coupons
             WHERE stock_id IN (${STOCK_ID}, ${EXPIRY_STOCK_ID});
        "
    )" || die "could not inspect coupons"

    history_count="$(
        mysql_q "
            SELECT COUNT(*)
              FROM coupon_history h
              JOIN coupons c ON c.coupon_id = h.coupon_id
             WHERE c.stock_id IN (${STOCK_ID}, ${EXPIRY_STOCK_ID});
        "
    )" || die "could not inspect coupon history"

    main_stock="$(
        mysql_q "
            SELECT remaining_stock
              FROM campaign_stocks
             WHERE stock_id=${STOCK_ID};
        "
    )" || die "could not inspect stock_id=$STOCK_ID"

    expiry_stock_count="$(
        mysql_q "
            SELECT remaining_stock
              FROM campaign_stocks
             WHERE stock_id=${EXPIRY_STOCK_ID};
        "
    )" || true

    info "DESTRUCTIVE TARGET"
    info "  database           : $DB_NAME @ $MYSQL_C"
    info "  coupons/history    : $coupon_count coupons, $history_count history rows"
    info "  main stock         : $STOCK_ID -> $INITIAL_STOCK (current=$main_stock)"
    info "  expiry stock       : $EXPIRY_STOCK_ID -> $EXPIRY_STOCK (current=${expiry_stock_count:-missing})"
    info "  redis DB           : $REDIS_C/$REDIS_DB"
    info "  expiration batch   : isolated DB only"

    if [[ "$SAVE_STRATEGY" == "kafka" ]]; then
        if kafka_sh \
            /opt/kafka/bin/kafka-topics.sh \
            --bootstrap-server localhost:9092 \
            --list 2>/dev/null |
            grep -qx "$DLT_TOPIC"; then

            dlt_count="$(
                kafka_sh \
                    /opt/kafka/bin/kafka-get-offsets.sh \
                    --bootstrap-server localhost:9092 \
                    --topic "$DLT_TOPIC" 2>/dev/null |
                awk -F: '{ s += $3 } END { print s + 0 }'
            )"

            info "  DLT                : $DLT_TOPIC ($dlt_count messages)"
        else
            info "  DLT                : $DLT_TOPIC (topic absent)"
        fi
    fi
}

# ------------------------------------------------------------------
# Kafka DLT
# ------------------------------------------------------------------

capture_dlt_baseline() {
    [[ "$SAVE_STRATEGY" == "kafka" ]] || return 0

    if kafka_sh \
        /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server localhost:9092 \
        --list 2>/dev/null |
        grep -qx "$DLT_TOPIC"; then

        DLT_BASELINE="$(
            kafka_sh \
                /opt/kafka/bin/kafka-get-offsets.sh \
                --bootstrap-server localhost:9092 \
                --topic "$DLT_TOPIC" 2>/dev/null |
            awk -F: '{ s += $3 } END { print s + 0 }'
        )"

        info "DLT baseline: $DLT_TOPIC messages=$DLT_BASELINE"
    else
        DLT_BASELINE=0
        info "DLT baseline: topic $DLT_TOPIC does not exist"
    fi
}

assert_dlt_unchanged() {
    [[ "$SAVE_STRATEGY" == "kafka" ]] || return 0

    if kafka_sh \
        /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server localhost:9092 \
        --list 2>/dev/null |
        grep -qx "$DLT_TOPIC"; then

        local now

        now="$(
            kafka_sh \
                /opt/kafka/bin/kafka-get-offsets.sh \
                --bootstrap-server localhost:9092 \
                --topic "$DLT_TOPIC" 2>/dev/null |
            awk -F: '{ s += $3 } END { print s + 0 }'
        )"

        [[ "$now" == "$DLT_BASELINE" ]] ||
            die \
                "DLT changed from $DLT_BASELINE to $now; shared DLT is never purged"
    fi
}

# ------------------------------------------------------------------
# verification diagnostics
# ------------------------------------------------------------------

print_verification_violations() {
    local run_id="$1"
    local url="$BASE/api/admin/batch/verification/runs/${run_id}/violations"
    local file="build/${run_id}-violations.json"
    local http_code

    printf '\n'
    step "7a. verification violations (runId=$run_id)"
    info "endpoint: $url"

    http_code="$(
        curl -sS \
            -o "$file" \
            -w '%{http_code}' \
            "$url" \
            2>"${file}.curl-error"
    )" || http_code="000"

    info "HTTP status: $http_code"

    if [[ -s "$file" ]]; then
        cat "$file"
        printf '\n'
        info "saved: $file"
    else
        info "violations response body is empty"
    fi

    if [[ -s "${file}.curl-error" ]]; then
        info "curl error:"
        sed 's/^/    /' "${file}.curl-error"
    fi
}

# ------------------------------------------------------------------
# round -> strategy
# ------------------------------------------------------------------

case "$ROUND" in
    V0)
        ISSUE_STRATEGY="NO_LOCK"
        SAVE_STRATEGY="sync-db"
        KAFKA_CONSUMER_ENABLED=false
        ;;
    V1)
        ISSUE_STRATEGY="PESSIMISTIC_LOCK"
        SAVE_STRATEGY="sync-db"
        KAFKA_CONSUMER_ENABLED=false
        ;;
    V2)
        ISSUE_STRATEGY="LUA_SCRIPT"
        SAVE_STRATEGY="sync-db"
        KAFKA_CONSUMER_ENABLED=false
        ;;
    V3)
        ISSUE_STRATEGY="LUA_SCRIPT"
        SAVE_STRATEGY="kafka"
        KAFKA_CONSUMER_ENABLED=true
        ;;
    *)
        die "unknown ROUND: $ROUND (use V0 | V1 | V2 | V3)"
        ;;
esac

USES_REDIS=false
[[ "$ISSUE_STRATEGY" == "LUA_SCRIPT" ]] && USES_REDIS=true

if [[ "$ROUND" == "V0" ]]; then
    FAIL_ON_VIOLATION=false
else
    FAIL_ON_VIOLATION=true
fi

if [[ -z "$RUN_ID" ]]; then
    RUN_ID="SMOKE-${ROUND}-$(date -u +%Y%m%d-%H%M%S)-$$"
fi

REPORT_FILE="${OUT_DIR}/${RUN_ID}.md"
RESPONSE_FILE="build/${RUN_ID}-responses.txt"
COUPON_FILE="build/${RUN_ID}-coupons.txt"
EXPIRY_RESPONSE_FILE="build/${RUN_ID}-expiry-responses.txt"

# ------------------------------------------------------------------
# preflight
# ------------------------------------------------------------------

require_command curl
require_command docker
require_command grep
require_command sed
require_command awk
require_command sort

assert_destructive_allowed

info "destructive acceptance gate: ALLOW_DESTRUCTIVE_ACCEPTANCE=true"

require_container "$MYSQL_C"
require_container "$REDIS_C"

if [[ "$SAVE_STRATEGY" == "kafka" ]]; then
    require_container "$KAFKA_C"
fi

mkdir -p build "$OUT_DIR"

prepare_acceptance_db

# ------------------------------------------------------------------
# 0. build
# ------------------------------------------------------------------

step "0. build"

if [[ "$SKIP_BUILD" == "true" ]]; then
    info "SKIP_BUILD=true, using existing jar"
    [[ -f "$JAR" ]] || die "jar not found: $JAR"
else
    ./gradlew bootJar --quiet ||
        die "bootJar failed"
fi

info "jar: $JAR"
info "git: $(git rev-parse --short HEAD 2>/dev/null || echo unknown)"

# ------------------------------------------------------------------
# 1. start
# ------------------------------------------------------------------

step "1. start application"
info "round=$ROUND issue=$ISSUE_STRATEGY save=$SAVE_STRATEGY consumer=$KAFKA_CONSUMER_ENABLED"
info "DB=$DB_NAME"

if curl -s -o /dev/null -w '%{http_code}' \
    "$BASE/actuator/health" |
    grep -q '^200$'; then

    die \
        "something is already listening on $BASE. stop it first; stale process may produce stale results."
fi

: > "$LOG"

SPRING_DATASOURCE_URL="jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" \
SPRING_DATASOURCE_USERNAME="$DB_USER" \
SPRING_DATASOURCE_PASSWORD="$DB_PASS" \
SPRING_JPA_DATABASE_PLATFORM="org.hibernate.dialect.MySQLDialect" \
SPRING_DATA_REDIS_DATABASE="$REDIS_DB" \
COUPON_STRATEGY="$ISSUE_STRATEGY" \
COUPON_SAVE_STRATEGY="$SAVE_STRATEGY" \
COUPON_KAFKA_CONSUMER_ENABLED="$KAFKA_CONSUMER_ENABLED" \
    java \
        -Duser.timezone=UTC \
        -jar "$JAR" \
        > "$LOG" 2>&1 &

APP_PID=$!

info "pid=$APP_PID"
info "log=$LOG"

for _ in $(seq 1 60); do
    if curl -s "$BASE/actuator/health" 2>/dev/null |
        grep -q '"status":"UP"'; then
        break
    fi

    if ! kill -0 "$APP_PID" 2>/dev/null; then
        die "application died during startup. see $LOG"
    fi

    sleep 1
done

curl -s "$BASE/actuator/health" |
    grep -q '"status":"UP"' ||
    die "application did not become healthy in 60s. see $LOG"

# ------------------------------------------------------------------
# 2. assert strategy
# ------------------------------------------------------------------

step "2. assert the running strategy"

grep -q "= ${ISSUE_STRATEGY} " "$LOG" ||
    die \
        "issue strategy is not ${ISSUE_STRATEGY}. see $LOG"

info "issue strategy confirmed: $ISSUE_STRATEGY"

# ------------------------------------------------------------------
# 3. reset
# ------------------------------------------------------------------

step "3. reset round data"
info "stock_id=$STOCK_ID -> $INITIAL_STOCK"

print_destructive_summary

mysql_q "
    DELETE h
      FROM coupon_history h
      JOIN coupons c
        ON c.coupon_id = h.coupon_id
     WHERE c.stock_id IN (${STOCK_ID}, ${EXPIRY_STOCK_ID});

    DELETE FROM coupons
     WHERE stock_id IN (${STOCK_ID}, ${EXPIRY_STOCK_ID});

    UPDATE campaign_stocks
       SET total_stock = ${INITIAL_STOCK},
           remaining_stock = ${INITIAL_STOCK}
     WHERE stock_id = ${STOCK_ID};

    UPDATE campaign_stocks
       SET total_stock = ${EXPIRY_STOCK},
           remaining_stock = ${EXPIRY_STOCK}
     WHERE stock_id = ${EXPIRY_STOCK_ID};
" || die "reset failed"

REMAINING="$(
    mysql_q "
        SELECT remaining_stock
          FROM campaign_stocks
         WHERE stock_id=${STOCK_ID};
    "
)"

[[ "$REMAINING" == "$INITIAL_STOCK" ]] ||
    die \
        "remaining_stock=$REMAINING, expected $INITIAL_STOCK"

EXPIRY_REMAINING="$(
    mysql_q "
        SELECT remaining_stock
          FROM campaign_stocks
         WHERE stock_id=${EXPIRY_STOCK_ID};
    "
)" || true

if [[ -n "$EXPIRY_REMAINING" ]]; then
    [[ "$EXPIRY_REMAINING" == "$EXPIRY_STOCK" ]] ||
        die \
            "expiry stock remaining=$EXPIRY_REMAINING, expected $EXPIRY_STOCK"
fi

LEFTOVER="$(
    mysql_q "
        SELECT COUNT(*)
          FROM coupons
         WHERE stock_id IN (${STOCK_ID}, ${EXPIRY_STOCK_ID});
    "
)"

[[ "$LEFTOVER" == "0" ]] ||
    die "reset left $LEFTOVER coupons behind"

info "main remaining_stock=$REMAINING"
info "expiry remaining_stock=${EXPIRY_REMAINING:-missing}"

capture_dlt_baseline

# ------------------------------------------------------------------
# 4. warm redis
# ------------------------------------------------------------------

step "4. warm redis"

if [[ "$USES_REDIS" == "true" ]]; then

    ROW="$(
        mysql_q "
            SET time_zone='+00:00';

            SELECT
                CAST(ROUND(UNIX_TIMESTAMP(c.open_at) * 1000) AS UNSIGNED),
                CAST(ROUND(UNIX_TIMESTAMP(c.expire_at) * 1000) AS UNSIGNED)
              FROM campaign_stocks cs
              JOIN campaigns c
                ON c.campaign_id = cs.campaign_id
             WHERE cs.stock_id = ${STOCK_ID};
        "
    )"

    read -r OPEN_MS EXPIRE_MS <<< "$ROW"

    [[ -n "${OPEN_MS:-}" ]] ||
        die "could not read campaign times for stock_id=$STOCK_ID"

    redis_cli MSET \
        "campaign:${CAMPAIGN_ID}:openAt" "$OPEN_MS" \
        "campaign:${CAMPAIGN_ID}:expireAt" "$EXPIRE_MS" \
        >/dev/null

    redis_cli DEL \
        "issued:${CAMPAIGN_ID}" \
        >/dev/null

    redis_cli MSET \
        "stock:${STOCK_ID}" "$INITIAL_STOCK" \
        "stockId:${CAMPAIGN_ID}:${ROUTE_ID}:${FARE_CLASS}" "$STOCK_ID" \
        >/dev/null

    info "campaign/stock keys cached for Redis round"

    mysql_q "
        SELECT CONCAT('SET stock:', stock_id, ' ', remaining_stock)
          FROM campaign_stocks
         WHERE stock_id <> ${STOCK_ID};
    " |
        docker exec -i "$REDIS_C" \
            redis-cli -n "$REDIS_DB" \
            >/dev/null

    info "other pools synced for reconciliation"

else

    redis_cli DEL \
        "campaign:${CAMPAIGN_ID}:openAt" \
        "campaign:${CAMPAIGN_ID}:expireAt" \
        "stock:${STOCK_ID}" \
        "stockId:${CAMPAIGN_ID}:${ROUTE_ID}:${FARE_CLASS}" \
        "issued:${CAMPAIGN_ID}" \
        >/dev/null

    info "DB-only round: campaign/stock Redis keys removed"
fi

# ------------------------------------------------------------------
# 5. issue
# ------------------------------------------------------------------

step "5. issue $REQUESTS concurrent requests"

: > "$RESPONSE_FILE"
: > "$COUPON_FILE"

ISSUE_PIDS=()

for i in $(seq 1 "$REQUESTS"); do
    (
        key="$(uuid4)"

        body="$(
            printf \
                '{"userId":%d,"campaignId":%d,"routeId":"%s","fareClass":"%s"}' \
                "$i" \
                "$CAMPAIGN_ID" \
                "$ROUTE_ID" \
                "$FARE_CLASS"
        )"

        resp="$(
            curl -s \
                -w '\n%{http_code}' \
                -X POST \
                "$BASE/api/coupons/issue" \
                -H 'Content-Type: application/json' \
                -H "Idempotency-Key: $key" \
                -d "$body"
        )"

        code="$(printf '%s' "$resp" | tail -n1)"
        payload="$(printf '%s' "$resp" | sed '$d')"

        reason="$(
            printf '%s' "$payload" |
                grep -o '"errorCode":"[A-Z_]*"' |
                head -1 |
                cut -d'"' -f4
        )"

        printf '%s %s\n' \
            "$code" \
            "${reason:-OK}" \
            >> "$RESPONSE_FILE"

        if [[ "$code" == "200" ]]; then
            cid="$(
                printf '%s' "$payload" |
                    grep -o '"couponId":"[0-9]*"' |
                    head -1 |
                    cut -d'"' -f4
            )"

            [[ -n "$cid" ]] &&
                printf '%s\n' "$cid" >> "$COUPON_FILE"
        fi
    ) &

    ISSUE_PIDS+=("$!")
done

for p in "${ISSUE_PIDS[@]}"; do
    wait "$p" 2>/dev/null || true
done

sort "$RESPONSE_FILE" |
    uniq -c |
    sort -rn |
    sed 's/^/    /'

TOTAL="$(wc -l < "$RESPONSE_FILE" | tr -d ' ')"

[[ "$TOTAL" == "$REQUESTS" ]] ||
    die \
        "only $TOTAL responses recorded, expected $REQUESTS"

SUCCESS="$(grep -c '^200 ' "$RESPONSE_FILE" || true)"
OUT_OF_STOCK="$(grep -c ' OUT_OF_STOCK$' "$RESPONSE_FILE" || true)"
ALREADY_ISSUED="$(grep -c ' ALREADY_ISSUED$' "$RESPONSE_FILE" || true)"
LOCK_TIMEOUT="$(grep -c ' LOCK_TIMEOUT$' "$RESPONSE_FILE" || true)"
CONCURRENCY_CONFLICT="$(grep -c ' CONCURRENCY_CONFLICT$' "$RESPONSE_FILE" || true)"
CONNECTION_UNAVAILABLE="$(grep -c ' CONNECTION_UNAVAILABLE$' "$RESPONSE_FILE" || true)"
SERVER_ERR="$(grep -c '^5' "$RESPONSE_FILE" || true)"

CAPTURED="$(wc -l < "$COUPON_FILE" | tr -d ' ')"

CLASSIFIED=$(
    (
        echo "$SUCCESS"
        echo "$OUT_OF_STOCK"
        echo "$ALREADY_ISSUED"
        echo "$LOCK_TIMEOUT"
        echo "$CONCURRENCY_CONFLICT"
        echo "$CONNECTION_UNAVAILABLE"
    ) |
    awk '{ s += $1 } END { print s + 0 }'
)

UNCLASSIFIED=$((TOTAL - CLASSIFIED))

info "success=$SUCCESS OUT_OF_STOCK=$OUT_OF_STOCK ALREADY_ISSUED=$ALREADY_ISSUED"
info "LOCK_TIMEOUT=$LOCK_TIMEOUT CONCURRENCY_CONFLICT=$CONCURRENCY_CONFLICT"
info "CONNECTION_UNAVAILABLE=$CONNECTION_UNAVAILABLE"
info "unclassified=$UNCLASSIFIED  5xx=$SERVER_ERR"
info "couponIds=$CAPTURED"

if [[ "$ROUND" == "V0" ]]; then

    info "V0 baseline — exact success/OOS counts are recorded, not asserted"

    [[ "$UNCLASSIFIED" == "0" ]] ||
        die \
            "$UNCLASSIFIED unclassified responses — see $RESPONSE_FILE"

else

    EXPECT_SUCCESS=$(
        if (( REQUESTS < INITIAL_STOCK )); then
            echo "$REQUESTS"
        else
            echo "$INITIAL_STOCK"
        fi
    )

    EXPECT_OOS=$((REQUESTS - EXPECT_SUCCESS))

    [[ "$SUCCESS" == "$EXPECT_SUCCESS" ]] ||
        die \
            "success=$SUCCESS, expected exactly $EXPECT_SUCCESS"

    [[ "$OUT_OF_STOCK" == "$EXPECT_OOS" ]] ||
        die \
            "OUT_OF_STOCK=$OUT_OF_STOCK, expected $EXPECT_OOS"

    [[ "$ALREADY_ISSUED" == "0" ]] ||
        die "ALREADY_ISSUED=$ALREADY_ISSUED, expected 0"

    [[ "$LOCK_TIMEOUT" == "0" ]] ||
        die "LOCK_TIMEOUT=$LOCK_TIMEOUT, expected 0"

    [[ "$CONCURRENCY_CONFLICT" == "0" ]] ||
        die "CONCURRENCY_CONFLICT=$CONCURRENCY_CONFLICT, expected 0"

    [[ "$CONNECTION_UNAVAILABLE" == "0" ]] ||
        die "CONNECTION_UNAVAILABLE=$CONNECTION_UNAVAILABLE, expected 0"

    [[ "$UNCLASSIFIED" == "0" ]] ||
        die \
            "$UNCLASSIFIED unclassified responses — see $RESPONSE_FILE"

    [[ "$SERVER_ERR" == "0" ]] ||
        die \
            "$SERVER_ERR server errors — acceptance requires zero"
fi

[[ "$CAPTURED" == "$SUCCESS" ]] ||
    die \
        "captured $CAPTURED couponIds but $SUCCESS successes — response shape changed?"

# ------------------------------------------------------------------
# 5b. use / cancel
# ------------------------------------------------------------------

if [[ "$TRANSITIONS" != "true" ]]; then
    die \
        "TRANSITIONS=false is diagnostic only; full acceptance requires state transitions and expiration"
fi

step "5b. state transitions (use $USE_COUNT, cancel $CANCEL_COUNT)"

# V3: wait until Kafka consumer persists issued coupons.
if [[ "$SAVE_STRATEGY" == "kafka" ]]; then
    DB_ISSUED=0

    for _ in $(seq 1 30); do
        DB_ISSUED="$(
            mysql_q "
                SELECT COUNT(*)
                  FROM coupons
                 WHERE stock_id=${STOCK_ID};
            "
        )"

        [[ "$DB_ISSUED" == "$SUCCESS" ]] && break
        sleep 1
    done
else
    DB_ISSUED="$(
        mysql_q "
            SELECT COUNT(*)
              FROM coupons
             WHERE stock_id=${STOCK_ID};
        "
    )"
fi

[[ "$DB_ISSUED" == "$SUCCESS" ]] ||
    die \
        "db holds $DB_ISSUED coupons but $SUCCESS were issued"

mapfile -t COUPON_IDS < "$COUPON_FILE"

[[ "${#COUPON_IDS[@]}" -ge "$USE_COUNT" ]] ||
    die \
        "USE_COUNT=$USE_COUNT but only ${#COUPON_IDS[@]} coupons available"

[[ "$CANCEL_COUNT" -le "$USE_COUNT" ]] ||
    die \
        "CANCEL_COUNT must be <= USE_COUNT"

for ((i = 0; i < USE_COUNT; i++)); do
    id="${COUPON_IDS[$i]}"

    code="$(
        curl -s \
            -o /dev/null \
            -w '%{http_code}' \
            -X POST \
            "$BASE/api/coupons/${id}/use" \
            -H "Idempotency-Key: $(uuid4)"
    )"

    [[ "$code" == "200" ]] ||
        die "use coupon $id returned HTTP $code"
done

info "used $USE_COUNT coupons"

for ((i = 0; i < CANCEL_COUNT; i++)); do
    id="${COUPON_IDS[$i]}"

    code="$(
        curl -s \
            -o /dev/null \
            -w '%{http_code}' \
            -X POST \
            "$BASE/api/coupons/${id}/cancel" \
            -H "Idempotency-Key: $(uuid4)"
    )"

    [[ "$code" == "200" ]] ||
        die "cancel coupon $id returned HTTP $code"
done

info "cancelled $CANCEL_COUNT coupons"

EXPECT_USED=$((USE_COUNT - CANCEL_COUNT))
EXPECT_ISSUED=$((SUCCESS - USE_COUNT))

ACT_USED="$(
    mysql_q "
        SELECT COUNT(*)
          FROM coupons
         WHERE stock_id=${STOCK_ID}
           AND status='USED';
    "
)"

ACT_CANCELLED="$(
    mysql_q "
        SELECT COUNT(*)
          FROM coupons
         WHERE stock_id=${STOCK_ID}
           AND status='CANCELLED';
    "
)"

ACT_ISSUED="$(
    mysql_q "
        SELECT COUNT(*)
          FROM coupons
         WHERE stock_id=${STOCK_ID}
           AND status='ISSUED';
    "
)"

info "stock $STOCK_ID -> ISSUED=$ACT_ISSUED USED=$ACT_USED CANCELLED=$ACT_CANCELLED"

[[ "$ACT_USED" == "$EXPECT_USED" ]] ||
    die "expected USED=$EXPECT_USED, got $ACT_USED"

[[ "$ACT_CANCELLED" == "$CANCEL_COUNT" ]] ||
    die "expected CANCELLED=$CANCEL_COUNT, got $ACT_CANCELLED"

[[ "$ACT_ISSUED" == "$EXPECT_ISSUED" ]] ||
    die "expected ISSUED=$EXPECT_ISSUED, got $ACT_ISSUED"

AFTER_STOCK="$(
    mysql_q "
        SELECT remaining_stock
          FROM campaign_stocks
         WHERE stock_id=${STOCK_ID};
    "
)"

if [[ "$ROUND" == "V0" ]]; then
    info \
        "V0 baseline — remaining_stock=$AFTER_STOCK recorded; exact stock delta not asserted"
else
    EXPECT_STOCK=$((INITIAL_STOCK - SUCCESS))

    [[ "$AFTER_STOCK" == "$EXPECT_STOCK" ]] ||
        die \
            "remaining_stock=$AFTER_STOCK, expected $EXPECT_STOCK"

    info "remaining_stock=$AFTER_STOCK; state changes do not restore stock"
fi

# ------------------------------------------------------------------
# 5c. expiration
# ------------------------------------------------------------------

step "5c. expiration batch"

mysql_q "
    SET time_zone='+00:00';

    INSERT INTO campaigns
        (campaign_id, name, open_at, expire_at)
    VALUES
        (
            ${EXPIRY_CAMPAIGN_ID},
            '${EXPIRY_CAMPAIGN_NAME}',
            NOW(3),
            DATE_ADD(NOW(3), INTERVAL ${EXPIRY_WINDOW_SEC} SECOND)
        )
    ON DUPLICATE KEY UPDATE
        name = VALUES(name),
        open_at = VALUES(open_at),
        expire_at = VALUES(expire_at);
" || die "expiry campaign upsert failed"

# Make absolutely sure the expiry pool starts empty.
mysql_q "
    DELETE h
      FROM coupon_history h
      JOIN coupons c
        ON c.coupon_id = h.coupon_id
     WHERE c.stock_id=${EXPIRY_STOCK_ID};

    DELETE FROM coupons
     WHERE stock_id=${EXPIRY_STOCK_ID};
" || die "could not clear expiry coupon data"

mysql_q "
    INSERT INTO campaign_stocks
        (
            stock_id,
            campaign_id,
            route_id,
            fare_class,
            total_stock,
            remaining_stock
        )
    VALUES
        (
            ${EXPIRY_STOCK_ID},
            ${EXPIRY_CAMPAIGN_ID},
            '${EXPIRY_ROUTE_ID}',
            '${EXPIRY_FARE_CLASS}',
            ${EXPIRY_STOCK},
            ${EXPIRY_STOCK}
        )
    ON DUPLICATE KEY UPDATE
        campaign_id = VALUES(campaign_id),
        route_id = VALUES(route_id),
        fare_class = VALUES(fare_class),
        total_stock = VALUES(total_stock),
        remaining_stock = VALUES(remaining_stock);
" || die "expiry stock upsert failed"

EXP_ROW="$(
    mysql_q "
        SET time_zone='+00:00';

        SELECT
            CAST(ROUND(UNIX_TIMESTAMP(open_at) * 1000) AS UNSIGNED),
            CAST(ROUND(UNIX_TIMESTAMP(expire_at) * 1000) AS UNSIGNED)
          FROM campaigns
         WHERE campaign_id=${EXPIRY_CAMPAIGN_ID};
    "
)"

read -r EXP_OPEN_MS EXP_EXPIRE_MS <<< "$EXP_ROW"

[[ -n "${EXP_OPEN_MS:-}" ]] ||
    die "could not read expiry campaign times"

if [[ "$USES_REDIS" == "true" ]]; then

    redis_cli MSET \
        "campaign:${EXPIRY_CAMPAIGN_ID}:openAt" "$EXP_OPEN_MS" \
        "campaign:${EXPIRY_CAMPAIGN_ID}:expireAt" "$EXP_EXPIRE_MS" \
        >/dev/null

    redis_cli DEL \
        "issued:${EXPIRY_CAMPAIGN_ID}" \
        >/dev/null

    redis_cli MSET \
        "stock:${EXPIRY_STOCK_ID}" "$EXPIRY_STOCK" \
        "stockId:${EXPIRY_CAMPAIGN_ID}:${EXPIRY_ROUTE_ID}:${EXPIRY_FARE_CLASS}" "$EXPIRY_STOCK_ID" \
        >/dev/null

else

    redis_cli DEL \
        "campaign:${EXPIRY_CAMPAIGN_ID}:openAt" \
        "campaign:${EXPIRY_CAMPAIGN_ID}:expireAt" \
        "stock:${EXPIRY_STOCK_ID}" \
        "stockId:${EXPIRY_CAMPAIGN_ID}:${EXPIRY_ROUTE_ID}:${EXPIRY_FARE_CLASS}" \
        "issued:${EXPIRY_CAMPAIGN_ID}" \
        >/dev/null
fi

info \
    "expiry campaign ready: campaign=$EXPIRY_CAMPAIGN_ID stock=$EXPIRY_STOCK_ID window=${EXPIRY_WINDOW_SEC}s"

USER_OK="$(
    mysql_q "
        SELECT COUNT(*)
          FROM users
         WHERE user_id BETWEEN
               $((EXPIRY_USER_BASE + 1))
           AND $((EXPIRY_USER_BASE + EXPIRY_REQUESTS));
    "
)"

[[ "$USER_OK" == "$EXPIRY_REQUESTS" ]] ||
    die \
        "expiry round needs users $((EXPIRY_USER_BASE + 1))..$((EXPIRY_USER_BASE + EXPIRY_REQUESTS)); only $USER_OK exist"

: > "$EXPIRY_RESPONSE_FILE"

EXPIRY_PIDS=()

for i in $(seq 1 "$EXPIRY_REQUESTS"); do
    (
        key="$(uuid4)"
        uid=$((EXPIRY_USER_BASE + i))

        body="$(
            printf \
                '{"userId":%d,"campaignId":%d,"routeId":"%s","fareClass":"%s"}' \
                "$uid" \
                "$EXPIRY_CAMPAIGN_ID" \
                "$EXPIRY_ROUTE_ID" \
                "$EXPIRY_FARE_CLASS"
        )"

        resp="$(
            curl -s \
                -w '\n%{http_code}' \
                -X POST \
                "$BASE/api/coupons/issue" \
                -H 'Content-Type: application/json' \
                -H "Idempotency-Key: $key" \
                -d "$body"
        )"

        code="$(printf '%s' "$resp" | tail -n1)"
        payload="$(printf '%s' "$resp" | sed '$d')"

        reason="$(
            printf '%s' "$payload" |
                grep -o '"errorCode":"[A-Z_]*"' |
                head -1 |
                cut -d'"' -f4
        )"

        printf '%s %s\n' \
            "$code" \
            "${reason:-OK}" \
            >> "$EXPIRY_RESPONSE_FILE"
    ) &

    EXPIRY_PIDS+=("$!")
done

for p in "${EXPIRY_PIDS[@]}"; do
    wait "$p" 2>/dev/null || true
done

EXP_TOTAL="$(wc -l < "$EXPIRY_RESPONSE_FILE" | tr -d ' ')"

[[ "$EXP_TOTAL" == "$EXPIRY_REQUESTS" ]] ||
    die \
        "only $EXP_TOTAL expiry responses recorded, expected $EXPIRY_REQUESTS"

EXP_SUCCESS="$(grep -c '^200 ' "$EXPIRY_RESPONSE_FILE" || true)"
EXP_OOS="$(grep -c ' OUT_OF_STOCK$' "$EXPIRY_RESPONSE_FILE" || true)"
EXP_ALREADY_ISSUED="$(grep -c ' ALREADY_ISSUED$' "$EXPIRY_RESPONSE_FILE" || true)"
EXP_LOCK_TIMEOUT="$(grep -c ' LOCK_TIMEOUT$' "$EXPIRY_RESPONSE_FILE" || true)"
EXP_CONCURRENCY_CONFLICT="$(grep -c ' CONCURRENCY_CONFLICT$' "$EXPIRY_RESPONSE_FILE" || true)"
EXP_CONNECTION_UNAVAILABLE="$(grep -c ' CONNECTION_UNAVAILABLE$' "$EXPIRY_RESPONSE_FILE" || true)"
EXP_5XX="$(grep -c '^5' "$EXPIRY_RESPONSE_FILE" || true)"

EXP_CLASSIFIED=$(
    (
        echo "$EXP_SUCCESS"
        echo "$EXP_OOS"
        echo "$EXP_ALREADY_ISSUED"
        echo "$EXP_LOCK_TIMEOUT"
        echo "$EXP_CONCURRENCY_CONFLICT"
        echo "$EXP_CONNECTION_UNAVAILABLE"
    ) |
    awk '{ s += $1 } END { print s + 0 }'
)

EXP_UNCLASSIFIED=$((EXP_TOTAL - EXP_CLASSIFIED))

if (( EXPIRY_REQUESTS < EXPIRY_STOCK )); then
    EXPECT_EXP_SUCCESS="$EXPIRY_REQUESTS"
else
    EXPECT_EXP_SUCCESS="$EXPIRY_STOCK"
fi

EXPECT_EXP_OOS=$((EXPIRY_REQUESTS - EXPECT_EXP_SUCCESS))

info "expiry campaign issue:"
info "success=$EXP_SUCCESS OUT_OF_STOCK=$EXP_OOS ALREADY_ISSUED=$EXP_ALREADY_ISSUED"
info "LOCK_TIMEOUT=$EXP_LOCK_TIMEOUT CONCURRENCY_CONFLICT=$EXP_CONCURRENCY_CONFLICT"
info "CONNECTION_UNAVAILABLE=$EXP_CONNECTION_UNAVAILABLE"
info "unclassified=$EXP_UNCLASSIFIED  5xx=$EXP_5XX"

if [[ "$ROUND" == "V0" ]]; then

    info "V0 baseline — exact expiry success/OOS counts are recorded, not asserted"

    [[ "$EXP_UNCLASSIFIED" == "0" ]] ||
        die \
            "$EXP_UNCLASSIFIED unclassified expiry responses"

else

    [[ "$EXP_SUCCESS" == "$EXPECT_EXP_SUCCESS" ]] ||
        die \
            "expiry success=$EXP_SUCCESS, expected $EXPECT_EXP_SUCCESS"

    [[ "$EXP_OOS" == "$EXPECT_EXP_OOS" ]] ||
        die \
            "expiry OUT_OF_STOCK=$EXP_OOS, expected $EXPECT_EXP_OOS"

    [[ "$EXP_ALREADY_ISSUED" == "0" ]] ||
        die \
            "expiry ALREADY_ISSUED=$EXP_ALREADY_ISSUED, expected 0"

    [[ "$EXP_LOCK_TIMEOUT" == "0" ]] ||
        die \
            "expiry LOCK_TIMEOUT=$EXP_LOCK_TIMEOUT, expected 0"

    [[ "$EXP_CONCURRENCY_CONFLICT" == "0" ]] ||
        die \
            "expiry CONCURRENCY_CONFLICT=$EXP_CONCURRENCY_CONFLICT, expected 0"

    [[ "$EXP_CONNECTION_UNAVAILABLE" == "0" ]] ||
        die \
            "expiry CONNECTION_UNAVAILABLE=$EXP_CONNECTION_UNAVAILABLE, expected 0"

    [[ "$EXP_UNCLASSIFIED" == "0" ]] ||
        die \
            "$EXP_UNCLASSIFIED unclassified expiry responses"

    [[ "$EXP_5XX" == "0" ]] ||
        die \
            "$EXP_5XX server errors on expiry campaign"
fi

# V3: wait until Kafka consumer persists expiry coupons.
if [[ "$SAVE_STRATEGY" == "kafka" ]]; then

    EXP_DB=0

    for _ in $(seq 1 30); do
        EXP_DB="$(
            mysql_q "
                SELECT COUNT(*)
                  FROM coupons
                 WHERE stock_id=${EXPIRY_STOCK_ID};
            "
        )"

        [[ "$EXP_DB" == "$EXP_SUCCESS" ]] && break
        sleep 1
    done
else
    EXP_DB="$(
        mysql_q "
            SELECT COUNT(*)
              FROM coupons
             WHERE stock_id=${EXPIRY_STOCK_ID};
        "
    )"
fi

[[ "$EXP_DB" == "$EXP_SUCCESS" ]] ||
    die \
        "expiry DB holds $EXP_DB coupons but $EXP_SUCCESS were issued"

WAIT_SEC=$((EXPIRY_WINDOW_SEC + 5))

info \
    "waiting ${WAIT_SEC}s for expiry window to close"

sleep "$WAIT_SEC"

ELIGIBLE="$(
    mysql_q "
        SELECT COUNT(*)
          FROM coupons
         WHERE status='ISSUED'
           AND expire_at <= NOW(3);
    "
)"

info \
    "eligible ISSUED coupons in isolated DB: $ELIGIBLE"

[[ "$ELIGIBLE" == "$EXP_SUCCESS" ]] ||
    die \
        "eligible coupons=$ELIGIBLE, expected exactly $EXP_SUCCESS"

LAUNCH="$(
    curl -fsS \
        -X POST \
        "$BASE/api/admin/batch/expiration?runId=${RUN_ID}-EXPIRE" \
        2>/dev/null
)" || die "could not launch expiration: $LAUNCH"

EXEC_ID="$(json_field "jobExecutionId" "$LAUNCH")"

[[ -n "$EXEC_ID" ]] ||
    die "could not extract expiration jobExecutionId: $LAUNCH"

STATUS="$(await_execution "$EXEC_ID" expiration)"

info "execution $EXEC_ID -> ${STATUS:-UNKNOWN}"

[[ "$STATUS" == "COMPLETED" ]] ||
    die "expiration ended as ${STATUS:-UNKNOWN}"

EXPIRED="$(
    mysql_q "
        SELECT COUNT(*)
          FROM coupons
         WHERE stock_id=${EXPIRY_STOCK_ID}
           AND status='EXPIRED';
    "
)"

NOT_EXPIRED="$(
    mysql_q "
        SELECT COUNT(*)
          FROM coupons
         WHERE stock_id=${EXPIRY_STOCK_ID}
           AND status<>'EXPIRED';
    "
)"

info \
    "expiry stock $EXPIRY_STOCK_ID -> EXPIRED=$EXPIRED other=$NOT_EXPIRED"

[[ "$EXPIRED" == "$EXP_SUCCESS" ]] ||
    die \
        "expected $EXP_SUCCESS expired, got $EXPIRED"

[[ "$NOT_EXPIRED" == "0" ]] ||
    die \
        "$NOT_EXPIRED coupons survived expiration batch"

STILL="$(
    mysql_q "
        SELECT COUNT(*)
          FROM coupons
         WHERE stock_id=${STOCK_ID}
           AND status='EXPIRED';
    "
)"

[[ "$STILL" == "0" ]] ||
    die \
        "$STILL main-round coupons were expired"

EXP_HISTORY="$(
    mysql_q "
        SELECT COUNT(*)
          FROM coupon_history h
          JOIN coupons c
            ON c.coupon_id=h.coupon_id
         WHERE c.stock_id=${EXPIRY_STOCK_ID}
           AND h.to_status='EXPIRED';
    "
)"

[[ "$EXP_HISTORY" == "$EXP_SUCCESS" ]] ||
    die \
        "expiration wrote $EXP_HISTORY history rows, expected $EXP_SUCCESS"

info "expiration history rows=$EXP_HISTORY"

# ------------------------------------------------------------------
# 6. Kafka settle
# ------------------------------------------------------------------

if [[ "$SAVE_STRATEGY" == "kafka" ]]; then

    step "6. wait for consumer lag 0"

    SETTLED=false

    for _ in $(seq 1 60); do

        LAG_SUM="$(
            kafka_sh \
                /opt/kafka/bin/kafka-consumer-groups.sh \
                --bootstrap-server localhost:9092 \
                --group coupon-service \
                --describe 2>/dev/null |
            awk '
                $1 == "coupon-service" && $6 ~ /^[0-9]+$/ {
                    s += $6
                }
                END {
                    print s + 0
                }
            '
        )"

        if [[ "$LAG_SUM" == "0" ]]; then
            SETTLED=true
            break
        fi

        info "lag=$LAG_SUM"
        sleep 2
    done

    [[ "$SETTLED" == "true" ]] ||
        die \
            "consumer lag did not reach 0 in 120s"

    info "lag 0"

    assert_dlt_unchanged

else

    step "6. wait for consumer lag 0 (skipped, save strategy=$SAVE_STRATEGY)"
fi

# ------------------------------------------------------------------
# 7. verification
# ------------------------------------------------------------------

if [[ "$ROUND" == "V0" ]]; then
    FAIL_ON_VIOLATION=false
else
    FAIL_ON_VIOLATION=true
fi

step "7. verification batch"
info "runId=$RUN_ID round=$ROUND failOnViolation=$FAIL_ON_VIOLATION"

LAUNCH="$(
    curl -fsS \
        -X POST \
        "$BASE/api/admin/batch/verification?runId=${RUN_ID}&round=${ROUND}&failOnViolation=${FAIL_ON_VIOLATION}" \
        2>/dev/null
)" || die "could not launch verification: $LAUNCH"

EXEC_ID="$(json_field "jobExecutionId" "$LAUNCH")"

[[ -n "$EXEC_ID" ]] ||
    die \
        "could not extract verification jobExecutionId: $LAUNCH"

STATUS="$(await_execution "$EXEC_ID" verification)"

info "execution $EXEC_ID -> ${STATUS:-UNKNOWN}"

if [[ "$STATUS" != "COMPLETED" ]]; then
    print_verification_violations "$RUN_ID"
    die \
        "verification ended as ${STATUS:-UNKNOWN}"
fi

# ------------------------------------------------------------------
# 8. reconciliation
# ------------------------------------------------------------------

step "8. reconciliation batch"

LAUNCH="$(
    curl -fsS \
        -X POST \
        "$BASE/api/admin/batch/reconcile?runId=${RUN_ID}" \
        2>/dev/null
)" || die "could not launch reconciliation: $LAUNCH"

EXEC_ID="$(json_field "jobExecutionId" "$LAUNCH")"

[[ -n "$EXEC_ID" ]] ||
    die \
        "could not extract reconciliation jobExecutionId: $LAUNCH"

STATUS="$(await_execution "$EXEC_ID" reconcile)"

info "execution $EXEC_ID -> ${STATUS:-UNKNOWN}"

[[ "$STATUS" == "COMPLETED" ]] ||
    die \
        "reconciliation ended as ${STATUS:-UNKNOWN}"

# ------------------------------------------------------------------
# 9. report
# ------------------------------------------------------------------

step "9. report"

curl -fsS \
    "$BASE/api/admin/batch/verification/runs/${RUN_ID}/report" \
    -o "$REPORT_FILE" ||
    die "could not fetch verification report"

[[ -s "$REPORT_FILE" ]] ||
    die "report is empty"

RULE_ROWS="$(
    grep -cE '^\| `[^`]+` \|' "$REPORT_FILE" || true
)"

[[ "$RULE_ROWS" -ge 15 ]] ||
    die \
        "report holds only $RULE_ROWS rule rows, expected 15 or more"

if grep -q '미실행 [1-9]' "$REPORT_FILE"; then
    die \
        "some verification rules did not run. see $REPORT_FILE"
fi

info "$REPORT_FILE ($RULE_ROWS rules)"

printf '\n'
sed -n '1,12p' "$REPORT_FILE"

VIOLATIONS="$(
    grep '^| 총 위반 | ' "$REPORT_FILE" |
        sed -E 's/.*\| 총 위반 \| ([0-9]+).*/\1/' |
        head -1
)"

[[ -n "$VIOLATIONS" ]] ||
    die \
        "could not extract total violations from $REPORT_FILE"

# ------------------------------------------------------------------
# acceptance verdict
# ------------------------------------------------------------------

if [[ "$ROUND" == "V0" ]]; then

    info \
        "V0 baseline — violations=$VIOLATIONS recorded; not used as acceptance gate"

    grep -q \
        '^| 판정 | \*\*BASELINE\*\*' \
        "$REPORT_FILE" ||
        die \
            "V0 report verdict is not BASELINE. see $REPORT_FILE"

else

    [[ "$VIOLATIONS" == "0" ]] ||
        die \
            "report holds $VIOLATIONS violations. see $REPORT_FILE"

    grep -q \
        '^| 판정 | \*\*통과\*\* |' \
        "$REPORT_FILE" ||
        die \
            "report verdict is not 통과. see $REPORT_FILE"
fi

# ------------------------------------------------------------------
# final state
# ------------------------------------------------------------------

step "final state distribution"

mysql_q "
    SELECT
        stock_id,
        status,
        COUNT(*)
      FROM coupons
     WHERE stock_id IN (${STOCK_ID}, ${EXPIRY_STOCK_ID})
     GROUP BY stock_id, status
     ORDER BY stock_id, status;
" | sed 's/^/    /'

HIST="$(
    mysql_q "
        SELECT COUNT(*)
          FROM coupon_history h
          JOIN coupons c
            ON c.coupon_id=h.coupon_id
         WHERE c.stock_id IN (${STOCK_ID}, ${EXPIRY_STOCK_ID});
    "
)"

info "history rows for this acceptance run=$HIST"

# ------------------------------------------------------------------
# success
# ------------------------------------------------------------------

step "PASSED"

info "round=$ROUND"
info "runId=$RUN_ID"
info "issue=$ISSUE_STRATEGY"
info "save=$SAVE_STRATEGY"
info "report=$REPORT_FILE"
info "violations=$VIOLATIONS"
