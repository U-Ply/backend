#!/usr/bin/env bash

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

usage() {
    cat >&2 <<'EOF'
Usage: run-level2.sh <V0|V1|V2|V3> <runId>

Environment:
  LEVEL2_PHASE=all|load|finalize  Default: all
  BASE_URL=http://host:8081      Default: http://localhost:8081
  TOTAL_REQUESTS=20000           Default: 20000
  VUS=500                        Default: 500
  USER_ID_START=1                Default: 1
  CAMPAIGN_ID=1                  Default: 1
  ROUTE_ID=JEJU                  Default: JEJU
  FARE_CLASS=ECONOMY             Default: ECONOMY
  MAX_DURATION=10m               Default: 10m
  SETTLEMENT_TIMEOUT_SEC=300     Default: 300
  BATCH_TIMEOUT_SEC=600          Default: 600
  ALLOW_DIRTY_WORKTREE=true      Allow a non-reproducible rehearsal only
EOF
}

round="${1:-}"
run_id="${2:-}"
phase="${LEVEL2_PHASE:-all}"

if [[ ! "${round}" =~ ^V[0-3]$ ]] || [[ -z "${run_id}" ]]; then
    usage
    exit 2
fi

if [[ ! "${run_id}" =~ ^[A-Za-z0-9._-]+$ ]]; then
    echo "runId may contain only letters, numbers, dot, underscore, and hyphen." >&2
    exit 2
fi

if [[ "${phase}" != "all" && "${phase}" != "load" && "${phase}" != "finalize" ]]; then
    usage
    exit 2
fi

cd "${PROJECT_ROOT}"

base_url="${BASE_URL:-http://localhost:8081}"
total_requests="${TOTAL_REQUESTS:-20000}"
vus="${VUS:-500}"
user_id_start="${USER_ID_START:-1}"
campaign_id="${CAMPAIGN_ID:-1}"
route_id="${ROUTE_ID:-JEJU}"
fare_class="${FARE_CLASS:-ECONOMY}"
max_duration="${MAX_DURATION:-10m}"
settlement_timeout_sec="${SETTLEMENT_TIMEOUT_SEC:-300}"
batch_timeout_sec="${BATCH_TIMEOUT_SEC:-600}"
result_dir="load-tests/results/${run_id}"
summary_file="${result_dir}/k6-summary.json"

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "Required command is missing: $1" >&2
        exit 1
    fi
}

write_environment() {
    local git_sha dirty_state
    git_sha="$(git rev-parse HEAD)"
    dirty_state="clean"
    if [[ -n "$(git status --porcelain)" ]]; then
        dirty_state="dirty"
    fi

    mkdir -p "${result_dir}"
    cat > "${result_dir}/environment-${phase}.md" <<EOF
# ${run_id} environment

| Item | Value |
| --- | --- |
| Recorded at UTC | $(date -u +'%Y-%m-%dT%H:%M:%SZ') |
| Git SHA | ${git_sha} |
| Worktree | ${dirty_state} |
| Round | ${round} |
| Phase | ${phase} |
| BASE_URL | ${base_url} |
| TOTAL_REQUESTS | ${total_requests} |
| VUS | ${vus} |
| USER_ID_START | ${user_id_start} |
| CAMPAIGN_ID | ${campaign_id} |
| ROUTE_ID | ${route_id} |
| FARE_CLASS | ${fare_class} |
| MAX_DURATION | ${max_duration} |
| Java | $(java -version 2>&1 | head -n 1) |
| Docker | $(docker --version 2>/dev/null || echo 'not recorded on load-only host') |
| k6 | $(k6 version 2>/dev/null | head -n 1 || echo 'not installed on finalize-only host') |
EOF


    if [[ ! -f "${result_dir}/result.md" ]]; then
        cp load-tests/templates/level2-result-template.md "${result_dir}/result.md"
    fi
}

check_reproducible_worktree() {
    if [[ -n "$(git status --porcelain)" && "${ALLOW_DIRTY_WORKTREE:-false}" != "true" ]]; then
        echo "The worktree is dirty. Commit the exact test source before an official run." >&2
        echo "For a rehearsal only, set ALLOW_DIRTY_WORKTREE=true." >&2
        exit 1
    fi
}

check_application_health() {
    if ! curl --silent --fail --max-time 5 "${base_url}/actuator/health" >/dev/null; then
        echo "Application health check failed: ${base_url}/actuator/health" >&2
        exit 1
    fi
}

run_load() {
    require_command k6
    check_application_health

    echo "Running k6: round=${round}, runId=${run_id}, requests=${total_requests}, vus=${vus}"
    k6 run \
        -e "TEST_STRATEGY=${round}" \
        -e "BASE_URL=${base_url}" \
        -e "TOTAL_REQUESTS=${total_requests}" \
        -e "VUS=${vus}" \
        -e "USER_ID_START=${user_id_start}" \
        -e "CAMPAIGN_ID=${campaign_id}" \
        -e "ROUTE_ID=${route_id}" \
        -e "FARE_CLASS=${fare_class}" \
        -e "MAX_DURATION=${max_duration}" \
        --summary-export "${summary_file}" \
        load-tests/k6/issue-level2.js \
        2>&1 | tee "${result_dir}/k6-console.log"
    k6_exit=${PIPESTATUS[0]}

    echo "${k6_exit}" > "${result_dir}/k6-exit-code.txt"
    if [[ ! -f "${summary_file}" ]]; then
        echo "k6 did not create ${summary_file}." >&2
        exit 1
    fi

    return "${k6_exit}"
}

kafka_lag() {
    docker exec coupon-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
        --bootstrap-server localhost:9092 \
        --describe \
        --group coupon-service 2>/dev/null \
        | awk 'NR > 1 && $1 != "GROUP" && $6 ~ /^[0-9]+$/ { sum += $6; found = 1 } END { if (found) print sum; else print -1 }'
}

dlt_count() {
    docker exec coupon-kafka /opt/kafka/bin/kafka-get-offsets.sh \
        --bootstrap-server localhost:9092 \
        --topic coupon-issued.DLT \
        --time -1 2>/dev/null \
        | awk -F: '$NF ~ /^[0-9]+$/ { sum += $NF; found = 1 } END { if (found) print sum; else print -1 }'
}

db_coupon_count() {
    docker exec coupon-mysql mysql -ucoupon -pcoupon1234 coupon_db -Nse \
        "SELECT COUNT(*) FROM coupons WHERE stock_id = 1;" 2>/dev/null
}

pending_count() {
    docker exec coupon-redis redis-cli --scan --pattern 'coupon:pending:*' 2>/dev/null \
        | awk 'NF { count++ } END { print count + 0 }'
}

wait_for_v3_settlement() {
    require_command jq
    local expected_count deadline lag dlt db_count pending
    expected_count="$(jq -r '.metrics.coupon_issued.values.count // 0' "${summary_file}")"
    deadline=$((SECONDS + settlement_timeout_sec))

    while (( SECONDS < deadline )); do
        lag="$(kafka_lag)"
        dlt="$(dlt_count)"
        db_count="$(db_coupon_count)"
        pending="$(pending_count)"
        echo "V3 settlement: expectedDb=${expected_count}, db=${db_count}, lag=${lag}, dlt=${dlt}, pending=${pending}"

        if [[ "${db_count}" == "${expected_count}" && "${lag}" == "0" && "${dlt}" == "0" && "${pending}" == "0" ]]; then
            cat > "${result_dir}/kafka-settlement.txt" <<EOF
settled_at_utc=$(date -u +'%Y-%m-%dT%H:%M:%SZ')
expected_db_coupons=${expected_count}
db_coupons=${db_count}
consumer_lag=${lag}
dlt_count=${dlt}
pending_count=${pending}
EOF
            return 0
        fi
        sleep 2
    done

    echo "V3 did not settle within ${settlement_timeout_sec} seconds." >&2
    return 1
}

launch_batch() {
    local job_key="$1"
    local url response execution_id

    url="${base_url}/api/admin/batch/${job_key}?runId=${run_id}"
    if [[ "${job_key}" == "verification-round" ]]; then
        url="${url}&round=${round}"
    elif [[ "${job_key}" == "verification" ]]; then
        url="${url}&round=${round}"
        if [[ "${round}" == "V0" ]]; then
            url="${url}&failOnViolation=false"
        else
            url="${url}&failOnViolation=true"
        fi
    else
        url="${url}&failOnViolation=true"
    fi

    response="$(curl --silent --show-error --fail -X POST "${url}")" || return 1
    printf '%s\n' "${response}" > "${result_dir}/${job_key}-launch.json"
    execution_id="$(jq -r '.jobExecutionId // empty' <<< "${response}")"
    if [[ -z "${execution_id}" ]]; then
        echo "${job_key} response did not include jobExecutionId." >&2
        return 1
    fi

    wait_for_batch "${job_key}" "${execution_id}"
}

wait_for_batch() {
    local job_key="$1"
    local execution_id="$2"
    local deadline response status
    deadline=$((SECONDS + batch_timeout_sec))

    while (( SECONDS < deadline )); do
        response="$(curl --silent --show-error --fail \
            "${base_url}/api/admin/batch/executions/${execution_id}")" || return 1
        status="$(jq -r '.status // empty' <<< "${response}")"
        printf '%s\n' "${response}" > "${result_dir}/${job_key}-execution.json"

        case "${status}" in
            COMPLETED)
                return 0
                ;;
            FAILED|STOPPED|ABANDONED)
                echo "${job_key} ended as ${status}." >&2
                return 1
                ;;
        esac
        sleep 2
    done

    echo "${job_key} did not finish within ${batch_timeout_sec} seconds." >&2
    return 1
}

capture_final_state() {
    docker exec -i coupon-mysql mysql -uroot -proot1234 \
        < load-tests/sql/verify-level2.sql \
        > "${result_dir}/db-verification.txt"
    docker exec coupon-redis redis-cli GET stock:1 > "${result_dir}/redis-stock.txt"
    docker exec coupon-redis redis-cli SCARD issued:1 > "${result_dir}/redis-issued-count.txt"

    if [[ "${round}" == "V3" ]]; then
        docker exec coupon-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
            --bootstrap-server localhost:9092 \
            --describe \
            --group coupon-service \
            > "${result_dir}/kafka-consumer-lag.txt"
        docker exec coupon-kafka /opt/kafka/bin/kafka-get-offsets.sh \
            --bootstrap-server localhost:9092 \
            --topic coupon-issued.DLT \
            --time -1 \
            > "${result_dir}/kafka-dlt-offsets.txt"
    fi
}

finalize_run() {
    require_command docker
    require_command curl
    require_command jq
    check_application_health

    if [[ ! -f "${summary_file}" ]]; then
        echo "Missing ${summary_file}. Copy it from the k6 host before finalize." >&2
        exit 1
    fi

    local final_exit=0
    if [[ "${round}" == "V3" ]]; then
        wait_for_v3_settlement || final_exit=1
    fi

    capture_final_state || final_exit=1
    launch_batch verification-round || final_exit=1

    curl --silent --show-error --fail \
        "${base_url}/api/admin/batch/verification/runs/${run_id}" \
        > "${result_dir}/verification-rules.json" || final_exit=1
    curl --silent --show-error --fail \
        "${base_url}/api/admin/batch/verification/runs/${run_id}/report" \
        > "${result_dir}/verification-report.md" || final_exit=1

    return "${final_exit}"
}

require_command git
require_command curl
check_reproducible_worktree
write_environment

overall_exit=0
if [[ "${phase}" == "all" || "${phase}" == "load" ]]; then
    run_load || overall_exit=1
fi

if [[ "${phase}" == "all" || "${phase}" == "finalize" ]]; then
    finalize_run || overall_exit=1
fi

echo "Level 2 ${round} artifacts: ${result_dir}"
exit "${overall_exit}"
