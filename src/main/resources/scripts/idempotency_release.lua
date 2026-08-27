-- KEYS[1]: 멱등성 키 (idempotency:{idempotencyKey})
-- ARGV[1]: 이 요청 실행의 ownerToken
--
-- PROCESSING 상태이고 ownerToken이 일치할 때만 삭제한다.
-- TTL 만료 후 다른 요청이 같은 키를 새로 선점했다면 ownerToken이 달라
-- 아무 것도 하지 않고 0을 반환한다 - 늦게 실패한 이전 요청이 새 PROCESSING을
-- 지우는 사고를 막는다.
local current = redis.call('GET', KEYS[1])
if not current then
    return 0
end

local value = cjson.decode(current)
if value.status ~= 'PROCESSING' or value.ownerToken ~= ARGV[1] then
    return 0
end

redis.call('DEL', KEYS[1])
return 1
