#!/usr/bin/env bash

set -euo pipefail

# Level 2 MySQL 시드를 Redis 발급 경로가 사용하는 키 구조로 복제한다.
# seed/reset 양쪽에서 이 파일을 호출해 Redis 초기 상태가 달라지는 것을 막는다.
db_row="$({
    docker exec coupon-mysql mysql -uroot -proot1234 -Nse "
        SET time_zone = '+00:00';
        SELECT cs.campaign_id,
               cs.stock_id,
               cs.route_id,
               cs.fare_class,
               cs.remaining_stock,
               CAST(ROUND(UNIX_TIMESTAMP(c.open_at) * 1000) AS UNSIGNED),
               CAST(ROUND(UNIX_TIMESTAMP(c.expire_at) * 1000) AS UNSIGNED)
          FROM coupon_db.campaign_stocks cs
          JOIN coupon_db.campaigns c ON c.campaign_id = cs.campaign_id
         WHERE cs.campaign_id = 1
           AND cs.stock_id = 1
           AND cs.route_id = 'JEJU'
           AND cs.fare_class = 'ECONOMY';
    "
} 2>/dev/null)"

if [[ -z "${db_row}" ]]; then
    echo "Level 2 campaign stock was not found in MySQL." >&2
    exit 1
fi

read -r campaign_id stock_id route_id fare_class remaining_stock open_at_millis expire_at_millis <<< "${db_row}"

docker exec coupon-redis redis-cli FLUSHDB >/dev/null
docker exec coupon-redis redis-cli MSET \
    "stock:${stock_id}" "${remaining_stock}" \
    "stockId:${campaign_id}:${route_id}:${fare_class}" "${stock_id}" \
    "campaign:${campaign_id}:openAt" "${open_at_millis}" \
    "campaign:${campaign_id}:expireAt" "${expire_at_millis}" >/dev/null

echo "Redis stock:${stock_id}=$(docker exec coupon-redis redis-cli GET "stock:${stock_id}")"
echo "Redis stockId mapping=$(docker exec coupon-redis redis-cli GET "stockId:${campaign_id}:${route_id}:${fare_class}")"
echo "Redis campaign openAt=$(docker exec coupon-redis redis-cli GET "campaign:${campaign_id}:openAt")"
echo "Redis campaign expireAt=$(docker exec coupon-redis redis-cli GET "campaign:${campaign_id}:expireAt")"
echo "Redis issued:${campaign_id} size=$(docker exec coupon-redis redis-cli SCARD "issued:${campaign_id}")"
