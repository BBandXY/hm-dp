-- 仅当批次号仍匹配时读取 processing，避免并发消费者把下一批数据记到旧批次下。
if redis.call('GET', KEYS[1]) ~= ARGV[1] then
    return {}
end
return redis.call('HGETALL', KEYS[2])
