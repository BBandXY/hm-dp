package com.hmdp.service.impl;

import com.hmdp.dto.BlogLikeDelta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlogLikeSyncJobTest {

    @Mock
    private BlogLikeRedisService redisService;

    @Mock
    private BlogLikeTransactionalService transactionalService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    @InjectMocks
    private BlogLikeSyncJob job;

    @BeforeEach
    void setUpLock() {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Test
    void shouldAckOnlyAfterDatabaseBatchSucceeds() {
        List<BlogLikeDelta> deltas = Collections.singletonList(new BlogLikeDelta(10L, 2L));
        when(redisService.claimPendingDeltas(anyString(), anyInt())).thenReturn("batch-1");
        when(redisService.readProcessingDeltas("batch-1")).thenReturn(deltas);
        when(transactionalService.applyDeltaBatch("batch-1", deltas))
                .thenReturn(BlogLikeTransactionalService.ApplyResult.APPLIED);
        when(redisService.acknowledgeDeltaBatch("batch-1")).thenReturn(true);

        job.flushPendingDeltas();

        verify(transactionalService).applyDeltaBatch("batch-1", deltas);
        verify(redisService).acknowledgeDeltaBatch("batch-1");
        verify(lock).unlock();
    }

    @Test
    void shouldKeepProcessingBatchWhenDatabaseFails() {
        List<BlogLikeDelta> deltas = Collections.singletonList(new BlogLikeDelta(10L, 2L));
        when(redisService.claimPendingDeltas(anyString(), anyInt())).thenReturn("batch-1");
        when(redisService.readProcessingDeltas("batch-1")).thenReturn(deltas);
        when(transactionalService.applyDeltaBatch("batch-1", deltas))
                .thenThrow(new IllegalStateException("database unavailable"));

        job.flushPendingDeltas();

        verify(redisService, never()).acknowledgeDeltaBatch("batch-1");
        verify(lock).unlock();
    }
}
