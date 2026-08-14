local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local now = tonumber(ARGV[4])

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local beginKey = 'seckill:begin:' .. voucherId
local endKey = 'seckill:end:' .. voucherId
local orderStatusKey = 'seckill:order:status:' .. orderId
local pendingKey = 'seckill:orders:pending'

local beginTime = tonumber(redis.call('get', beginKey))
local endTime = tonumber(redis.call('get', endKey))
local stock = tonumber(redis.call('get', stockKey))

if (beginTime == nil or endTime == nil or stock == nil) then
    return 5
end
if (now < beginTime) then
    return 3
end
if (now > endTime) then
    return 4
end
if (stock <= 0) then
    return 1
end
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
redis.call('hset', orderStatusKey,
        'orderId', orderId,
        'userId', userId,
        'voucherId', voucherId,
        'status', 'PENDING',
        'updatedAt', ARGV[4])
redis.call('expire', orderStatusKey, 172800)
redis.call('zadd', pendingKey, now, orderId)
redis.call('xadd', 'stream.orders', '*',
        'id', orderId,
        'orderId', orderId,
        'userId', userId,
        'voucherId', voucherId,
        'createdAt', ARGV[4])

return 0
