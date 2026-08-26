#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

usage() {
    echo "Usage: $0 <V0|V1|V2|V3> [--seed]" >&2
}

round="${1:-}"
mode="${2:-}"

if [[ ! "${round}" =~ ^V[0-3]$ ]]; then
    usage
    exit 2
fi

if [[ -n "${mode}" && "${mode}" != "--seed" ]]; then
    usage
    exit 2
fi

cd "${PROJECT_ROOT}"

if ! command -v curl >/dev/null 2>&1; then
    echo "Required command is missing: curl" >&2
    exit 1
fi

base_url="${BASE_URL:-http://localhost:8081}"
if curl --silent --fail --max-time 2 "${base_url}/actuator/health" >/dev/null 2>&1; then
    echo "Application is still reachable at ${base_url}." >&2
    echo "Stop every application instance before resetting Level 2 data." >&2
    exit 1
fi

if [[ "${mode}" == "--seed" ]]; then
    LEVEL2_SEED_CONFIRM=SEED bash scripts/load-test/seed-level2.sh
else
    LEVEL2_RESET_CONFIRM=RESET bash scripts/load-test/reset-level2.sh
fi

if [[ "${round}" == "V3" ]]; then
    LEVEL2_KAFKA_CONFIRM=RESET_KAFKA bash scripts/load-test/reset-level2-kafka.sh
fi

db_state="$(docker exec coupon-mysql mysql -uroot -proot1234 -Nse "
    SELECT CONCAT(
        (SELECT COUNT(*) FROM coupon_db.coupons), ' ',
        (SELECT COUNT(*) FROM coupon_db.coupon_history), ' ',
        (SELECT remaining_stock FROM coupon_db.campaign_stocks WHERE stock_id = 1)
    );
" 2>/dev/null)"
redis_stock="$(docker exec coupon-redis redis-cli GET stock:1 2>/dev/null)"
redis_issued="$(docker exec coupon-redis redis-cli SCARD issued:1 2>/dev/null)"

if [[ "${db_state}" != "0 0 10000" || "${redis_stock}" != "10000" || "${redis_issued}" != "0" ]]; then
    echo "Level 2 initial-state validation failed." >&2
    echo "Expected: DB coupons/history/stock='0 0 10000', Redis stock/issued='10000 0'" >&2
    echo "Actual:   DB='${db_state}', Redis='${redis_stock} ${redis_issued}'" >&2
    exit 1
fi

case "${round}" in
    V0)
        issue_strategy="NO_LOCK"
        save_strategy="sync-db"
        consumer_enabled="false"
        ;;
    V1)
        issue_strategy="PESSIMISTIC_LOCK"
        save_strategy="sync-db"
        consumer_enabled="false"
        ;;
    V2)
        issue_strategy="LUA_SCRIPT"
        save_strategy="sync-db"
        consumer_enabled="false"
        ;;
    V3)
        issue_strategy="LUA_SCRIPT"
        save_strategy="kafka"
        consumer_enabled="true"
        ;;
esac

echo
echo "Level 2 ${round} data preparation completed."
echo "Initial state verified: DB coupons=0, history=0, stock=10000; Redis stock=10000, issued=0."
echo "Start every application instance with exactly these values:"
echo
cat <<EOF
COUPON_STRATEGY=${issue_strategy}
COUPON_SAVE_STRATEGY=${save_strategy}
COUPON_KAFKA_CONSUMER_ENABLED=${consumer_enabled}
COUPON_IDEMPOTENCY_ENABLED=false
RECONCILIATION_SCHEDULER_ENABLED=false
EOF
echo
echo "Then run:"
echo "  ./scripts/load-test/run-level2.sh ${round} L2-${round}-01"
