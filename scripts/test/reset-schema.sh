#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

cd "${PROJECT_ROOT}"

echo "WARNING: This will DROP and recreate every table in coupon_db."
echo "All local coupon, history, campaign, stock, user, and verification data will be deleted."
read -r -p "Type RESET to continue: " confirmation

if [[ "${confirmation}" != "RESET" ]]; then
    echo "Cancelled. No data was changed."
    exit 1
fi

docker compose up -d mysql

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

docker exec -i coupon-mysql mysql -uroot -proot1234 < docs/schema.sql

echo "Schema reset completed."
