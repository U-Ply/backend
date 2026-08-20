-- KEYS[1]: stockIdKey (e.g. stock:10)
-- KEYS[2]: issuedCampaignKey (e.g. issued:1)
-- ARGV[1]: userId

-- 1. Set에서 유저 제거 시도 (제거된 요소 개수 반환: 성공 시 1, 없었으면 0)
local removed = redis.call('SREM', KEYS[2], ARGV[1])

-- 2. 실제 제거에 성공한 경우(즉, 차감되었던 유저인 경우)에만 재고 +1
if removed == 1 then
    redis.call('INCRBY', KEYS[1], 1)
    return 1 -- 정상 복구 완료
end

return 0 -- 이미 복구되었거나 발급된 적 없는 유저 (무시)