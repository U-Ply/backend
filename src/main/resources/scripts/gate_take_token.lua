-- 발급 API 앞단 토큰 버킷 게이트.
--
-- KEYS[1] : 버킷 키 (Hash: field 'tokens', 'ts')
-- ARGV[1] : capacity      (버킷 최대 토큰)
-- ARGV[2] : refillPerSec  (초당 리필 토큰)
-- ARGV[3] : nowMillis     (호출자 = JVM 시계. Redis TIME 을 쓰지 않아 시계 일관성 유지)
-- ARGV[4] : need          (이번에 필요한 토큰 수, 보통 1)
--
-- 반환: 1 = 통과(토큰 차감됨), 0 = 거부(토큰 부족)

local key  = KEYS[1]
local cap  = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local now  = tonumber(ARGV[3])
local need = tonumber(ARGV[4])

-- 설정이 비정상이면 게이트를 무력화한다(발급을 막는 쪽으로 실패하지 않는다).
if cap == nil or rate == nil or cap <= 0 or rate <= 0 then
    return 1
end

local bucket = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local ts     = tonumber(bucket[2])

if tokens == nil or ts == nil then
    -- 첫 요청: 버킷을 가득 채운 상태로 시작한다.
    tokens = cap
    ts = now
end

-- 지난 시각 이후 흐른 시간만큼 리필하고 capacity 로 상한을 둔다.
local elapsed = now - ts
if elapsed < 0 then
    elapsed = 0
end
tokens = math.min(cap, tokens + (elapsed / 1000.0) * rate)

local allowed = 0
if tokens >= need then
    tokens = tokens - need
    allowed = 1
end

redis.call('HSET', key, 'tokens', tokens, 'ts', now)
-- 유휴 버킷 정리용 TTL: 버킷이 가득 차기까지 시간 + 1초 여유.
local ttlMs = math.ceil((cap / rate) * 1000) + 1000
redis.call('PEXPIRE', key, ttlMs)

return allowed
