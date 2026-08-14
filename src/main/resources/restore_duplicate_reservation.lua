if (redis.call('setnx', KEYS[2], '1') == 0) then
    return 0
end
redis.call('expire', KEYS[2], 604800)
redis.call('incr', KEYS[1])
return 1
