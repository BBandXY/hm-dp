package com.hmdp.service.impl;

import com.hmdp.dto.BlogLikeCount;
import com.hmdp.dto.BlogLikeDelta;
import com.hmdp.mapper.BlogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 点赞计数的数据库事务边界。
 *
 * <p>批次幂等记录和计数更新在同一事务中提交：如果数据库更新失败，批次记录也会回滚；
 * 如果提交成功但 Redis ACK 失败，重试时通过 batch_id 识别为已经执行。</p>
 */
@Slf4j
@Service
public class BlogLikeTransactionalService {

    private final BlogMapper blogMapper;

    public BlogLikeTransactionalService(BlogMapper blogMapper) {
        this.blogMapper = blogMapper;
    }

    @Transactional
    public ApplyResult applyDeltaBatch(String batchId, List<BlogLikeDelta> deltas) {
        if (deltas == null || deltas.isEmpty()) {
            return ApplyResult.EMPTY;
        }
        validateBatch(batchId, deltas);

        int inserted = blogMapper.insertLikeSyncBatch(batchId, deltas.size());
        if (inserted == 0) {
            return ApplyResult.IDEMPOTENT;
        }

        int updated = blogMapper.batchIncrementLiked(deltas);
        if (updated < deltas.size()) {
            // 笔记可能已被删除。该批次仍可提交，避免一条无效数据永久阻塞后续增量。
            log.warn("点赞增量批次存在未匹配笔记，batchId={}, expected={}, updated={}",
                    batchId, deltas.size(), updated);
        }
        return ApplyResult.APPLIED;
    }

    @Transactional
    public int reconcileLikeCounts(List<BlogLikeCount> counts) {
        if (counts == null || counts.isEmpty()) {
            return 0;
        }
        return blogMapper.batchReconcileLiked(counts);
    }

    @Transactional
    public int deleteSyncBatchesBefore(LocalDateTime cutoff, String activeBatchId) {
        return blogMapper.deleteLikeSyncBatchesBefore(cutoff, activeBatchId);
    }

    private void validateBatch(String batchId, List<BlogLikeDelta> deltas) {
        if (batchId == null || batchId.isEmpty() || batchId.length() > 64) {
            throw new IllegalArgumentException("点赞同步批次号不合法");
        }
        for (BlogLikeDelta delta : deltas) {
            if (delta == null || delta.getBlogId() == null || delta.getBlogId() <= 0L
                    || delta.getDelta() == null || delta.getDelta() == 0L) {
                throw new IllegalArgumentException("点赞同步批次包含非法增量，batchId=" + batchId);
            }
        }
    }

    public enum ApplyResult {
        APPLIED,
        IDEMPOTENT,
        EMPTY
    }
}
