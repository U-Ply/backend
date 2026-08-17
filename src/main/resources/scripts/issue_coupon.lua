-- KEYS[1]: 재고 Key (stock:{stockId})
-- KEYS[2]: 캠페인 중복 검사 Key (issued:{campaignId})
-- KEYS[3]: 캠페인 오픈 시각 Key (campaign:{campaignId}:openAt, UTC epoch milliseconds)
-- ARGV[1]: userId

-- 1. [정시 오픈 검사] Redis 서버 시간을 기준으로 오픈 전 요청을 차단한다.
local openAt = redis.call('GET', KEYS[3])
if not openAt then
    return -3 -- SYSTEM_ERROR: 웜업 데이터 누락
end

local redisTime = redis.call('TIME')
local nowMillis = (tonumber(redisTime[1]) * 1000) + math.floor(tonumber(redisTime[2]) / 1000)
if nowMillis < tonumber(openAt) then
    return -4 -- CAMPAIGN_NOT_OPEN
end

-- 2. [중복 검사] 해당 캠페인에서 이미 발급받은 유저인가?
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -1 -- ALREADY_ISSUED
end

-- 3. [재고 조회] stockId 기반 재고 수량 조회
local currentStock = redis.call('GET', KEYS[1])

if not currentStock or tonumber(currentStock) <= 0 then
    return -2 -- OUT_OF_STOCK
end

-- 4. [차감 및 기록] 재고 1 차감 + 유저를 캠페인 발급 목록에 추가
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])

-- 5. 성공 시 1 반환 (stockId는 이미 Java 단에서 알고 있으므로 반환 불필요)
return 1
