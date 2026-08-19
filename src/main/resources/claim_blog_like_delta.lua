-- 将有限数量的 pending 增量原子切换为 processing 快照。
-- 新到达的点赞仍写 pending，因此数据库 I/O 不会阻塞点赞请求。

local activeBatch = redis.call('GET', KEYS[4])
if activeBatch then
    return activeBatch
end

-- 理论上 processing 与 batch 元数据由同一 Lua 同时产生。若元数据被人工删除，
-- 为现有快照补一个新批次号；后续绝对值对账会修复极端情况下的重复增量。
if redis.call('HLEN', KEYS[3]) > 0 then
    redis.call('SET', KEYS[4], ARGV[1])
    return ARGV[1]
end

local maxItems = tonumber(ARGV[2])
local blogIds = redis.call('ZRANGE', KEYS[2], 0, maxItems - 1)
if #blogIds == 0 then
    return ''
end

for _, blogId in ipairs(blogIds) do
    local delta = redis.call('HGET', KEYS[1], blogId)
    redis.call('ZREM', KEYS[2], blogId)
    if delta then
        redis.call('HDEL', KEYS[1], blogId)
        if tonumber(delta) ~= 0 then
            redis.call('HSET', KEYS[3], blogId, delta)
        end
    end
end

if redis.call('HLEN', KEYS[3]) == 0 then
    return ''
end
redis.call('SET', KEYS[4], ARGV[1])
return ARGV[1]
