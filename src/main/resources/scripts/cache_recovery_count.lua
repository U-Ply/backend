-- KEYS[1]: 캐시 미스 카운트 키
-- ARGV[1]: 시간창(초)
--
-- INCR과 최초 EXPIRE를 하나의 스크립트로 묶어 원자적으로 처리한다. 두 명령을 따로
-- 실행하면 INCR 성공 후 EXPIRE 전에 장애가 나서 TTL 없는 카운트 키가 남을 수 있고,
-- 그러면 시간창과 무관하게 서로 멀리 떨어진 캐시 미스까지 계속 누적된다.
local count = redis.call('INCR', KEYS[1])
if count == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return count
