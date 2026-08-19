-- KEYS[1] 该笔记的点赞关系 ZSet
-- KEYS[2] 尚未切批的点赞净增量 Hash
-- KEYS[3] 待切批 blogId ZSet
-- KEYS[4] Redis 实时逻辑点赞数 Hash
-- KEYS[5] 历史基线点赞数 Hash
-- KEYS[6] 待对账 blogId ZSet
-- ARGV[1] userId, ARGV[2] blogId, ARGV[3] 当前毫秒时间, ARGV[4] 数据库初始点赞数；-1 表示尚未查询

local userId = ARGV[1]
local blogId = ARGV[2]
local now = ARGV[3]
local initialLiked = tonumber(ARGV[4])

local currentLiked = redis.call('HGET', KEYS[4], blogId)
if not currentLiked then
    -- 只有 Redis 冷启动后第一次操作该笔记时才需要调用方回源数据库。
    if initialLiked < 0 then
        return 0
    end

    local relationCount = redis.call('ZCARD', KEYS[1])
    local baseline = initialLiked - relationCount
    if baseline < 0 then
        baseline = 0
    end
    currentLiked = baseline + relationCount
    redis.call('HSET', KEYS[5], blogId, baseline)
    redis.call('HSET', KEYS[4], blogId, currentLiked)
end

-- 兼容从旧版本升级时已经存在 count、但尚未建立 baseline 的数据。
if not redis.call('HGET', KEYS[5], blogId) then
    local baseline = tonumber(currentLiked) - redis.call('ZCARD', KEYS[1])
    if baseline < 0 then
        baseline = 0
    end
    redis.call('HSET', KEYS[5], blogId, baseline)
end
redis.call('ZADD', KEYS[6], 'NX', 0, blogId)

local change
if redis.call('ZSCORE', KEYS[1], userId) then
    redis.call('ZREM', KEYS[1], userId)
    change = -1
else
    redis.call('ZADD', KEYS[1], now, userId)
    change = 1
end

local logicalCount = redis.call('HINCRBY', KEYS[4], blogId, change)
if logicalCount < 0 then
    -- 防御人工改数等异常情况，数据库 liked 为无符号数，不能写入负值。
    redis.call('HSET', KEYS[4], blogId, 0)
end

local pendingDelta = redis.call('HINCRBY', KEYS[2], blogId, change)
if pendingDelta == 0 then
    -- 点赞后又取消时，本周期净变化为 0，不需要产生落库工作。
    redis.call('HDEL', KEYS[2], blogId)
    redis.call('ZREM', KEYS[3], blogId)
else
    -- NX 保留第一次变脏的时间，避免热点笔记因为持续点击而一直排不到。
    redis.call('ZADD', KEYS[3], 'NX', now, blogId)
end

return change
