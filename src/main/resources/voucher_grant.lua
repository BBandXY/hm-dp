local stockKey = KEYS[1]
local userKey = KEYS[2]
local requestKey = KEYS[3]
local streamKey = KEYS[4]

local requestId = ARGV[1]
local userId = ARGV[2]
local voucherId = ARGV[3]
local taskProgressId = ARGV[4]
local source = ARGV[5]
local expireAt = ARGV[6]
local now = ARGV[7]

-- 同一个 requestId 的网络重试直接返回成功受理，不重复扣减库存。
if (redis.call('exists', requestKey) == 1) then
    return 3
end

local stock = tonumber(redis.call('get', stockKey))
if (stock == nil) then
    return 4
end
if (stock <= 0) then
    return 1
end
if (redis.call('sismember', userKey, userId) == 1) then
    return 2
end

redis.call('decr', stockKey)
redis.call('sadd', userKey, userId)
redis.call('set', requestKey, userId .. ':' .. voucherId, 'EX', 604800)
redis.call('xadd', streamKey, '*',
        'requestId', requestId,
        'userId', userId,
        'voucherId', voucherId,
        'taskProgressId', taskProgressId,
        'source', source,
        'expireAt', expireAt,
        'createdAt', now)
return 0
