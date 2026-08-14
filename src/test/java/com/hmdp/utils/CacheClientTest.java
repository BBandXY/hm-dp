package com.hmdp.utils;

import com.hmdp.entity.Shop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheClientTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    private CacheClient cacheClient;

    @BeforeEach
    void setUp() throws InterruptedException {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redissonClient.getLock("lock:shop:1")).thenReturn(lock);
        when(lock.tryLock(300L, 30000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        cacheClient = new CacheClient(stringRedisTemplate, redissonClient);
    }

    @AfterEach
    void tearDown() {
        cacheClient.shutdown();
    }

    @Test
    void shouldLoadDatabaseAndInitializeLogicalCacheOnFirstMiss() {
        when(valueOperations.get("cache:shop:1")).thenReturn(null, null);
        Shop shop = new Shop().setId(1L).setName("test-shop");

        Shop result = cacheClient.queryWithLogicalExpire(
                "cache:shop:",
                1L,
                Shop.class,
                ignored -> shop,
                30L,
                TimeUnit.MINUTES
        );

        assertEquals(shop, result);
        verify(valueOperations).set(eq("cache:shop:1"), contains("test-shop"));
        verify(lock).unlock();
    }
}
