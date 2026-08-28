#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
scenario="${1:-}"

case "${scenario}" in
    hotkey)
        sql_file="load-tests/sql/configure-hotkey-stocks.sql"
        ;;
    multi-stock)
        sql_file="load-tests/sql/configure-multi-stock.sql"
        ;;
    *)
        echo "Usage: configure-scenario-stocks.sh <hotkey|multi-stock>" >&2
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
docker exec -i coupon-mysql mysql --default-character-set=utf8mb4 -uroot -proot1234 < "${sql_file}"
bash scripts/load-test/initialize-level2-redis.sh

echo "Scenario stock configuration completed: ${scenario}"
