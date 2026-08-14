if (redis.call('setnx', KEYS[3], '1') == 0) then
    return 0
end
redis.call('expire', KEYS[3], 604800)
if (redis.call('srem', KEYS[2], ARGV[1]) == 1) then
    redis.call('incr', KEYS[1])
    return 1
end
return 2
