-- 只允许处理当前批次的消费者清理 processing，旧消费者不能误删下一批。
if redis.call('GET', KEYS[2]) ~= ARGV[1] then
    return 0
end
redis.call('DEL', KEYS[1], KEYS[2])
return 1
