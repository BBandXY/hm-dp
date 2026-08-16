local stockKey = KEYS[1]
local userKey = KEYS[2]
local requestKey = KEYS[3]

local expectedRequestValue = ARGV[1]
local userId = ARGV[2]
local removeUserReservation = ARGV[3]

if (redis.call('get', requestKey) ~= expectedRequestValue) then
    return 0
end

redis.call('incr', stockKey)
if (removeUserReservation == '1') then
    redis.call('srem', userKey, userId)
end
redis.call('del', requestKey)
return 1
