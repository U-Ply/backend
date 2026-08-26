-- KEYS[1]: 멱등성 키 (idempotency:{idempotencyKey})
-- ARGV[1]: 이 요청 실행의 ownerToken
-- ARGV[2]: 이 요청의 requestHash
-- ARGV[3]: COMPLETED 상태로 저장할 JSON 값
-- ARGV[4]: TTL(밀리초)
--
-- PROCESSING 상태 + ownerToken 일치 + requestHash 일치할 때만 COMPLETED로 교체한다.
-- 셋 중 하나라도 다르면(TTL 만료 후 다른 요청이 선점했거나, 소유권을 잃었거나) 현재 값을
-- 건드리지 않고 0을 반환한다 - 늦게 성공한 이전 요청이 다른 요청의 PROCESSING 위에
-- 덮어쓰는 사고를 막는다.
local current = redis.call('GET', KEYS[1])
if not current then
    return 0
end

local value = cjson.decode(current)
if value.status ~= 'PROCESSING'
        or value.ownerToken ~= ARGV[1]
        or value.requestHash ~= ARGV[2] then
    return 0
end

redis.call('SET', KEYS[1], ARGV[3], 'PX', ARGV[4])
return 1
