-- KEYS[1]: 멱등성 키 (idempotency:{idempotencyKey})
-- ARGV[1]: 이 요청 실행의 ownerToken
-- ARGV[2]: 연장할 TTL(밀리초)
--
-- PROCESSING 상태이고 ownerToken이 일치하는 소유자만 TTL을 연장(lease 갱신)할 수 있다.
local current = redis.call('GET', KEYS[1])
if not current then
    return 0
end

local value = cjson.decode(current)
if value.status ~= 'PROCESSING' or value.ownerToken ~= ARGV[1] then
    return 0
end

redis.call('PEXPIRE', KEYS[1], ARGV[2])
return 1
