#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

cd "${PROJECT_ROOT}"

echo "WARNING: This replaces all coupon_db and Redis data with the Level 2 seed."
confirmation="${LEVEL2_SEED_CONFIRM:-}"
if [[ -z "${confirmation}" ]]; then
    read -r -p "Type SEED to continue: " confirmation
fi

if [[ "${confirmation}" != "SEED" ]]; then
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
    echo "MySQL did not become ready within 60 seconds."
    exit 1
fi

docker exec -i coupon-mysql mysql --default-character-set=utf8mb4 -uroot -proot1234 \
    < load-tests/sql/seed-level2.sql

bash scripts/load-test/initialize-level2-redis.sh
echo "Level 2 seed completed."
