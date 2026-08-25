-- KEYS[1]: 재고 Key (stock:{stockId})
-- KEYS[2]: 캠페인 중복 검사 Key (issued:{campaignId})
-- KEYS[3]: 캠페인 오픈 시각 Key (campaign:{campaignId}:openAt, UTC epoch milliseconds)
-- KEYS[4]: 캠페인 만료 시각 Key (campaign:{campaignId}:expireAt, UTC epoch milliseconds)
-- ARGV[1]: userId
-- 반환: {resultCode, nowMillis, expireAtMillis}
--   resultCode      1=성공, -1=중복, -2=재고소진, -3=웜업누락, -4=오픈전, -5=만료
--   nowMillis       Redis TIME 기준 UTC epoch milliseconds (발급 성립 시각)
--   expireAtMillis  이 스크립트가 실제로 판정에 사용한 만료 시각. Java가 이 스크립트를 부르기
--                   전에 같은 키를 따로 읽어두더라도, 그 사이 웜업 복구가 값을 바꿨을 수 있다.
--                   DB에 쓰는 expire_at은 Java가 미리 읽은 값이 아니라 이 반환값을 써야
--                   Lua의 판정과 DB에 남는 값이 항상 같다. 실패 경로에서는 0(무의미)일 수 있다.
--
-- 오픈/만료 판정을 Java가 아니라 이 스크립트에서 하는 이유는 원자성 때문이다.
-- Java에서 먼저 검사하면 검사 통과와 재고 차감 사이에 캠페인이 만료될 수 있고,
-- 그 창에서 만료 후 발급이 성립한다. 판정과 차감이 같은 스크립트 안에 있어야 그 창이 닫힌다.
-- 같은 이유로 판정 기준 시각과 기록되는 issued_at이 모두 nowMillis 하나에서 나온다.

-- 0. [기준 시각 확보] 모든 반환 경로가 같은 시계를 싣도록 가장 먼저 읽는다.
local redisTime = redis.call('TIME')
local nowMillis = (tonumber(redisTime[1]) * 1000) + math.floor(tonumber(redisTime[2]) / 1000)

-- 1. [정시 오픈 검사] Redis 서버 시간을 기준으로 오픈 전 요청을 차단한다.
-- 경계는 정책 C-2를 따른다: open_at 정각은 발급 허용이므로 미만일 때만 거부한다.
local openAt = redis.call('GET', KEYS[3])
if not openAt then
    return {-3, nowMillis, 0} -- CAMPAIGN_NOT_CACHED: 웜업 데이터 누락
end

if nowMillis < tonumber(openAt) then
    return {-4, nowMillis, 0} -- CAMPAIGN_NOT_OPEN
end

-- 2. [만료 검사] 종료된 캠페인의 발급을 차단한다.
-- 경계는 정책 C-2를 따른다: expire_at 정각은 발급 거부이므로 이상일 때 거부한다.
-- (INV-11이 issued_at >= expire_at 을 위반으로 잡는 것과 같은 기준이다)
local expireAt = redis.call('GET', KEYS[4])
if not expireAt then
    return {-3, nowMillis, 0} -- CAMPAIGN_NOT_CACHED: 웜업 데이터 누락
end
local expireAtMillis = tonumber(expireAt)

if nowMillis >= expireAtMillis then
    return {-5, nowMillis, expireAtMillis} -- CAMPAIGN_EXPIRED
end

-- 3. [중복 검사] 해당 캠페인에서 이미 발급받은 유저인가?
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return {-1, nowMillis, expireAtMillis} -- ALREADY_ISSUED
end

-- 4. [재고 조회] stockId 기반 재고 수량 조회
local currentStock = redis.call('GET', KEYS[1])

if not currentStock then
    return {-3, nowMillis, expireAtMillis} -- CAMPAIGN_NOT_CACHED: (stockId:남은재고) 웜업 데이터 누락
end

if tonumber(currentStock) <= 0 then
    return {-2, nowMillis, expireAtMillis} -- OUT_OF_STOCK
end

-- 5. [차감 및 기록] 재고 1 차감 + 유저를 캠페인 발급 목록에 추가
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])

-- 6. 성공 (stockId는 이미 Java 단에서 알고 있으므로 반환 불필요)
return {1, nowMillis, expireAtMillis}
