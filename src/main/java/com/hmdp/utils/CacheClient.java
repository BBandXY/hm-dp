package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/** 通用缓存访问器，支持空值防穿透和逻辑过期的 stale-while-revalidate。 */
@Slf4j
@Component
public class CacheClient {

    private static final int JITTER_PERCENT = 10;
    private static final long MISS_LOCK_WAIT_MILLIS = 300L;
    private static final long REBUILD_LOCK_LEASE_SECONDS = 30L;

    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final ThreadPoolExecutor cacheRebuildExecutor = new ThreadPoolExecutor(
            2,
            4,
            60L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(200),
            r -> {
                Thread thread = new Thread(r, "cache-rebuild-worker");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );

    public CacheClient(StringRedisTemplate stringRedisTemplate, RedissonClient redissonClient) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
    }

    @PreDestroy
    public void shutdown() {
        cacheRebuildExecutor.shutdownNow();
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        long ttlSeconds = withJitter(unit.toSeconds(time));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), ttlSeconds, TimeUnit.SECONDS);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(withJitter(unit.toSeconds(time))));
        // 不设置物理过期时间，逻辑过期后仍可返回旧值，后台重建失败也不会形成缓存击穿。
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit
    ) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }

        R result = dbFallback.apply(id);
        if (result == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        set(key, result, time, unit);
        return result;
    }

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit
    ) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            if (json != null) {
                return null;
            }
            return initializeMissingCache(key, id, type, dbFallback, time, unit);
        }

        RedisData redisData;
        R cachedValue;
        try {
            redisData = JSONUtil.toBean(json, RedisData.class);
            cachedValue = convertData(redisData.getData(), type);
        } catch (Exception e) {
            log.error("缓存数据反序列化失败，将删除坏数据并回源，key={}", key, e);
            stringRedisTemplate.delete(key);
            return initializeMissingCache(key, id, type, dbFallback, time, unit);
        }

        if (redisData.getExpireTime() != null && redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return cachedValue;
        }

        scheduleRebuild(key, id, type, dbFallback, time, unit);
        return cachedValue;
    }

    private <R, ID> R initializeMissingCache(
            String key,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit
    ) {
        RLock lock = redissonClient.getLock(LOCK_SHOP_KEY + id);
        boolean locked = false;
        try {
            locked = lock.tryLock(MISS_LOCK_WAIT_MILLIS, REBUILD_LOCK_LEASE_SECONDS * 1000L, TimeUnit.MILLISECONDS);
            if (!locked) {
                String rebuiltJson = stringRedisTemplate.opsForValue().get(key);
                return parseLogicalValue(rebuiltJson, type);
            }

            String doubleCheckedJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(doubleCheckedJson)) {
                return parseLogicalValue(doubleCheckedJson, type);
            }
            if (doubleCheckedJson != null) {
                return null;
            }

            R loaded = dbFallback.apply(id);
            if (loaded == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            setWithLogicalExpire(key, loaded, time, unit);
            return loaded;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("缓存首次加载等待锁时被中断，key={}", key);
            return null;
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private <R, ID> void scheduleRebuild(
            String key,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit
    ) {
        try {
            cacheRebuildExecutor.execute(() -> rebuildIfNecessary(key, id, type, dbFallback, time, unit));
        } catch (RejectedExecutionException e) {
            log.warn("缓存重建队列已满，继续返回旧值，key={}", key);
        }
    }

    private <R, ID> void rebuildIfNecessary(
            String key,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit
    ) {
        RLock lock = redissonClient.getLock(LOCK_SHOP_KEY + id);
        boolean locked = false;
        try {
            locked = lock.tryLock(0L, REBUILD_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }

            String latestJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(latestJson)) {
                RedisData latest = JSONUtil.toBean(latestJson, RedisData.class);
                if (latest.getExpireTime() != null && latest.getExpireTime().isAfter(LocalDateTime.now())) {
                    return;
                }
            }

            R loaded = dbFallback.apply(id);
            if (loaded == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            } else {
                setWithLogicalExpire(key, loaded, time, unit);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("缓存重建线程被中断，key={}", key);
        } catch (Exception e) {
            // 保留旧缓存，下一次请求仍可以读取旧值并再次触发重建。
            log.error("缓存异步重建失败，已保留旧值，key={}", key, e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private <R> R parseLogicalValue(String json, Class<R> type) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        return convertData(redisData.getData(), type);
    }

    private <R> R convertData(Object data, Class<R> type) {
        if (data == null) {
            return null;
        }
        if (data instanceof JSONObject) {
            return JSONUtil.toBean((JSONObject) data, type);
        }
        return JSONUtil.toBean(JSONUtil.toJsonStr(data), type);
    }

    private long withJitter(long baseSeconds) {
        long safeBase = Math.max(1L, baseSeconds);
        long jitterBound = Math.max(1L, safeBase * JITTER_PERCENT / 100L);
        return safeBase + ThreadLocalRandom.current().nextLong(jitterBound + 1L);
    }
}
