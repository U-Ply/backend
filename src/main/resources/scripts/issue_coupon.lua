-- KEYS[1]: 재고 Key (stock:{stockId})
-- KEYS[2]: 캠페인 중복 검사 Key (issued:{campaignId})
-- ARGV[1]: userId

-- 1. [중복 검사] 해당 캠페인에서 이미 발급받은 유저인가?
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -1 -- ALREADY_ISSUED
end

-- 2. [재고 조회] stockId 기반 재고 수량 조회
local currentStock = redis.call('GET', KEYS[1])

if not currentStock or tonumber(currentStock) <= 0 then
    return -2 -- OUT_OF_STOCK
end

-- 3. [차감 및 기록] 재고 1 차감 + 유저를 캠페인 발급 목록에 추가
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])

-- 4. 성공 시 1 반환 (stockId는 이미 Java 단에서 알고 있으므로 반환 불필요)
return 1