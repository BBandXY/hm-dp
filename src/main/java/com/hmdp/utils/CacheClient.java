package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    private StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value,Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);

    }

    public void setWithLogicalExpire(String key, Object value,Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R,ID>R queryWithPassTheough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,Long time, TimeUnit unit){
        String key=keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断
        if (StrUtil.isNotBlank(json)){
             JSONUtil.toBean(json, Shop.class);
            return JSONUtil.toBean(json, type);
        }
        if (json!=null){
            return null;
        }
        //存在 返回
        //不存在查数据库
        R r= dbFallback.apply(id);
        //不存在 返回错误
        if ( r== null){
            //将空值写入
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //存在写redis
       this.set(key,r,time,unit);
        //返回
        return r;
    }

    private static  final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R,ID>R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit){
        String key=keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断
        if (StrUtil.isBlank(json)){
            return null;
        }
        //命中，先把json反序列化
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r= JSONUtil.toBean((JSONObject)redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        //未过期
        //过期
        //缓存重建
        //获取 锁
        String lockKey=LOCK_SHOP_KEY+id;
        boolean islock = trylock(lockKey);
        //成功，开启独立线程
        if (islock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        return r;
    }

    private boolean trylock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);

    }

    private void  unlock(String key){
        stringRedisTemplate.delete(key);
    }

}
