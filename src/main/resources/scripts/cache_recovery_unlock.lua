-- KEYS[1]: 락 키
-- ARGV[1]: 이 인스턴스가 락 선점 시 사용한 고유 토큰
--
-- 토큰이 일치할 때만 삭제하는 compare-and-delete. 단순 DEL을 쓰면, 복구가 오래 걸려
-- TTL이 먼저 만료된 뒤 다른 인스턴스가 같은 캠페인의 락을 새로 잡았을 때 그 락을
-- 대신 지워버리는 사고가 날 수 있다.
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
else
    return 0
end
