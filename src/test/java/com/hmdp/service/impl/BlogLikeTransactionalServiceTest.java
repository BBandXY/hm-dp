package com.hmdp.service.impl;

import com.hmdp.dto.BlogLikeCount;
import com.hmdp.dto.BlogLikeDelta;
import com.hmdp.mapper.BlogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlogLikeTransactionalServiceTest {

    @Mock
    private BlogMapper blogMapper;

    @InjectMocks
    private BlogLikeTransactionalService service;

    @Test
    void shouldApplyNewDeltaBatch() {
        List<BlogLikeDelta> deltas = Arrays.asList(
                new BlogLikeDelta(10L, 3L),
                new BlogLikeDelta(20L, -1L)
        );
        when(blogMapper.insertLikeSyncBatch("batch-1", 2)).thenReturn(1);
        when(blogMapper.batchIncrementLiked(deltas)).thenReturn(2);

        BlogLikeTransactionalService.ApplyResult result = service.applyDeltaBatch("batch-1", deltas);

        assertEquals(BlogLikeTransactionalService.ApplyResult.APPLIED, result);
        verify(blogMapper).batchIncrementLiked(deltas);
    }

    @Test
    void shouldSkipAlreadyAppliedBatch() {
        List<BlogLikeDelta> deltas = Collections.singletonList(new BlogLikeDelta(10L, 1L));
        when(blogMapper.insertLikeSyncBatch("batch-1", 1)).thenReturn(0);

        BlogLikeTransactionalService.ApplyResult result = service.applyDeltaBatch("batch-1", deltas);

        assertEquals(BlogLikeTransactionalService.ApplyResult.IDEMPOTENT, result);
        verify(blogMapper, never()).batchIncrementLiked(any());
    }

    @Test
    void shouldIgnoreEmptyBatchWithoutWritingDatabase() {
        BlogLikeTransactionalService.ApplyResult result =
                service.applyDeltaBatch("batch-empty", Collections.emptyList());

        assertEquals(BlogLikeTransactionalService.ApplyResult.EMPTY, result);
        verify(blogMapper, never()).insertLikeSyncBatch(anyString(), anyInt());
        verify(blogMapper, never()).batchIncrementLiked(any());
    }

    @Test
    void shouldBatchReconcileAbsoluteCounts() {
        List<BlogLikeCount> counts = Collections.singletonList(new BlogLikeCount(10L, 8L));
        when(blogMapper.batchReconcileLiked(counts)).thenReturn(1);

        assertEquals(1, service.reconcileLikeCounts(counts));
        verify(blogMapper).batchReconcileLiked(counts);
    }
}
