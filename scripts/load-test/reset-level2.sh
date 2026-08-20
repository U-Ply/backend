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

bash scripts/load-test/initialize-level2-redis.sh
echo "Level 2 reset completed."
