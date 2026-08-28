#!/usr/bin/env bash

set -euo pipefail

# MySQL의 campaign 1 재고 풀 전체를 Redis 발급 경로가 사용하는 키 구조로 복제한다.
# Level 2, 핫키, 다중 재고 풀의 seed/reset에서 같은 초기화 로직을 사용한다.
db_rows="$({
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
         ORDER BY cs.stock_id;
    "
} 2>/dev/null)"

if [[ -z "${db_rows}" ]]; then
    echo "Campaign stocks were not found in MySQL." >&2
    exit 1
fi

docker exec coupon-redis redis-cli FLUSHDB >/dev/null

while read -r campaign_id stock_id route_id fare_class remaining_stock open_at_millis expire_at_millis; do
    docker exec coupon-redis redis-cli MSET \
        "stock:${stock_id}" "${remaining_stock}" \
        "stockId:${campaign_id}:${route_id}:${fare_class}" "${stock_id}" \
        "campaign:${campaign_id}:openAt" "${open_at_millis}" \
        "campaign:${campaign_id}:expireAt" "${expire_at_millis}" >/dev/null

    echo "Redis stock:${stock_id}=$(docker exec coupon-redis redis-cli GET "stock:${stock_id}")"
    echo "Redis mapping ${route_id}/${fare_class}=$(docker exec coupon-redis redis-cli GET "stockId:${campaign_id}:${route_id}:${fare_class}")"
done <<< "${db_rows}"

echo "Redis issued:1 size=$(docker exec coupon-redis redis-cli SCARD issued:1)"
