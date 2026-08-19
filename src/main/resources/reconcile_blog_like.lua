-- 只有该 blogId 既不在 pending、也不在 processing 时才能做绝对值对账。
-- 检查与读取在同一个 Lua 内完成；脚本返回后新产生的增量会在后续批次正常叠加。

local blogId = ARGV[1]
if redis.call('HEXISTS', KEYS[2], blogId) == 1
        or redis.call('HEXISTS', KEYS[3], blogId) == 1 then
    return -1
end

local baseline = redis.call('HGET', KEYS[4], blogId)
if not baseline then
    return -1
end

local expected = tonumber(baseline) + redis.call('ZCARD', KEYS[1])
redis.call('HSET', KEYS[5], blogId, expected)
return expected
