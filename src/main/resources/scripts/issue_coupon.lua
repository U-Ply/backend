-- KEYS[1]: Mapping Key (stock-map:{campaignId}:{routeId}:{fareClassId})
-- KEYS[2]: 캠페인 중복 검사 Key (issued:{campaignId})
-- ARGV[1]: userId

-- 1. [중복 검사] 해당 캠페인에서 이미 발급받은 유저인가?
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return {-1, ""} -- 이미 발급됨
end

-- 2. [Mapping 조회] 복합 조건으로 stockId 조회
local stockId = redis.call('GET', KEYS[1])
if not stockId then
    return {-3, ""} -- 매핑 정보 없음 (캐시 워밍 누락)
end

-- 3. [재고 조회] stockId 기반 재고 Key 구성
local stockKey = 'stock:' .. stockId
local currentStock = redis.call('GET', stockKey)

if not currentStock or tonumber(currentStock) <= 0 then
    return {-2, ""} -- 품절 (재고 없음)
end

-- 4. [차감 및 기록] 재고 1 차감 + 유저를 캠페인 발급 목록에 추가
redis.call('DECR', stockKey)
redis.call('SADD', KEYS[2], ARGV[1])

-- 5. 성공 시 stockId 반환
return {1, tostring(stockId)}