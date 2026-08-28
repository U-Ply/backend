#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
scenario="${1:-}"

case "${scenario}" in
    level2)
        sql_file="load-tests/sql/configure-level2-stock.sql"
        ;;
    hotkey)
        sql_file="load-tests/sql/configure-hotkey-stocks.sql"
        ;;
    multi-stock)
        sql_file="load-tests/sql/configure-multi-stock.sql"
        ;;
    *)
        echo "Usage: configure-scenario-stocks.sh <level2|hotkey|multi-stock>" >&2
        exit 2
        ;;
esac

cd "${PROJECT_ROOT}"

echo "WARNING: This deletes coupons, histories, reports and campaign stocks for scenario=${scenario}."
confirmation="${SCENARIO_CONFIG_CONFIRM:-}"
if [[ -z "${confirmation}" ]]; then
    read -r -p "Type CONFIGURE to continue: " confirmation
fi

if [[ "${confirmation}" != "CONFIGURE" ]]; then
    echo "Cancelled. No data was changed."
    exit 1
fi

docker compose up -d mysql redis

mysql_ready=false
for _ in {1..30}; do
    if docker exec coupon-mysql mysqladmin ping -uroot -proot1234 --silent >/dev/null 2>&1; then
        mysql_ready=true
        break
    fi
    echo "Waiting for MySQL..."
    sleep 2
done

if [[ "${mysql_ready}" != "true" ]]; then
    echo "MySQL did not become ready within 60 seconds." >&2
    exit 1
fi

redis_ready=false
for _ in {1..30}; do
    if [[ "$(docker exec coupon-redis redis-cli ping 2>/dev/null)" == "PONG" ]]; then
        redis_ready=true
        break
    fi
    echo "Waiting for Redis..."
    sleep 2
done

if [[ "${redis_ready}" != "true" ]]; then
    echo "Redis did not become ready within 60 seconds." >&2
    exit 1
fi

docker exec -i coupon-mysql mysql --default-character-set=utf8mb4 -uroot -proot1234 < "${sql_file}"
bash scripts/load-test/initialize-level2-redis.sh

echo "Scenario stock configuration completed: ${scenario}"
