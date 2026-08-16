#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

cd "${PROJECT_ROOT}"

echo "WARNING: This deletes the previous Level 2 run results from MySQL and Redis."
read -r -p "Type RESET to continue: " confirmation

if [[ "${confirmation}" != "RESET" ]]; then
    echo "Cancelled. No data was changed."
    exit 1
fi

docker compose up -d mysql redis
docker exec -i coupon-mysql mysql -uroot -proot1234 < load-tests/sql/reset-level2-db.sql

docker exec coupon-redis redis-cli FLUSHDB >/dev/null
docker exec coupon-redis redis-cli SET stock:1 10000 >/dev/null

echo "Redis stock:1=$(docker exec coupon-redis redis-cli GET stock:1)"
echo "Redis issued:1 size=$(docker exec coupon-redis redis-cli SCARD issued:1)"
echo "Level 2 reset completed."
