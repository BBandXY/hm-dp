package com.hmdp.service.impl;

import com.hmdp.dto.BlogLikeCount;
import com.hmdp.dto.BlogLikeDelta;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_FLUSH_LOCK;
import static com.hmdp.utils.RedisConstants.BLOG_LIKED_RECONCILE_LOCK;

/**
 * 将 Redis 中聚合后的点赞净增量批量刷新到 MySQL，并低频执行绝对值对账。
 *
 * <p>Redisson 锁用于减少多实例重复工作；真正的正确性由 Redis 原子切批、数据库 batch_id
 * 唯一键以及带批次校验的 ACK 共同保证，锁短暂失效也不会重复累加。</p>
 */
@Slf4j
@Component
public class BlogLikeSyncJob {

    private final BlogLikeRedisService redisService;
    private final BlogLikeTransactionalService transactionalService;
    private final RedissonClient redissonClient;

    @Value("${hmdp.blog-like.batch-size:200}")
    private int batchSize;

    @Value("${hmdp.blog-like.reconcile-batch-size:200}")
    private int reconcileBatchSize;

    @Value("${hmdp.blog-like.reconcile-interval-ms:600000}")
    private long reconcileIntervalMs;

    @Value("${hmdp.blog-like.sync-log-retention-days:7}")
    private long syncLogRetentionDays;

    public BlogLikeSyncJob(
            BlogLikeRedisService redisService,
            BlogLikeTransactionalService transactionalService,
            RedissonClient redissonClient
    ) {
        this.redisService = redisService;
        this.transactionalService = transactionalService;
        this.redissonClient = redissonClient;
    }

    @Scheduled(
            initialDelayString = "${hmdp.blog-like.flush-initial-delay-ms:5000}",
            fixedDelayString = "${hmdp.blog-like.flush-delay-ms:1000}"
    )
    public void flushPendingDeltas() {
        RLock lock = redissonClient.getLock(BLOG_LIKED_FLUSH_LOCK);
        if (!lock.tryLock()) {
            return;
        }
        try {
            flushOneBatch();
        } catch (Exception e) {
            // 不 ACK 即保留 processing，下个调度周期会领取同一批次并重试。
            log.error("点赞增量落库失败，当前 processing 批次将在稍后重试", e);
        } finally {
            unlockIfHeld(lock);
        }
    }

    private void flushOneBatch() {
        String requestedBatchId = UUID.randomUUID().toString().replace("-", "");
        String batchId = redisService.claimPendingDeltas(requestedBatchId, Math.max(batchSize, 1));
        if (batchId == null) {
            return;
        }

        List<BlogLikeDelta> deltas = redisService.readProcessingDeltas(batchId);
        if (deltas.isEmpty()) {
            // 旧消费者落后于批次切换或异常恢复时可能读到空快照；条件 ACK 不会误删新批次。
            log.warn("未读取到匹配的点赞 processing 快照，执行条件 ACK，batchId={}", batchId);
            redisService.acknowledgeDeltaBatch(batchId);
            return;
        }

        BlogLikeTransactionalService.ApplyResult result =
                transactionalService.applyDeltaBatch(batchId, deltas);
        if (!redisService.acknowledgeDeltaBatch(batchId)) {
            log.warn("点赞增量已落库但 Redis ACK 未生效，将由幂等重试或对账收敛，batchId={}", batchId);
            return;
        }
        log.debug("点赞增量批次处理完成，batchId={}, size={}, result={}",
                batchId, deltas.size(), result);
    }

    @Scheduled(
            initialDelayString = "${hmdp.blog-like.reconcile-initial-delay-ms:30000}",
            fixedDelayString = "${hmdp.blog-like.reconcile-delay-ms:60000}"
    )
    public void reconcileLikeCounts() {
        RLock lock = redissonClient.getLock(BLOG_LIKED_RECONCILE_LOCK);
        if (!lock.tryLock()) {
            return;
        }
        try {
            reconcileOneBatch();
        } catch (Exception e) {
            log.error("点赞计数对账失败，本轮数据将在稍后重试", e);
        } finally {
            unlockIfHeld(lock);
        }
    }

    private void reconcileOneBatch() {
        long now = System.currentTimeMillis();
        long cutoff = now - Math.max(reconcileIntervalMs, 0L);
        List<Long> dueIds = redisService.findDueReconciliationIds(
                cutoff,
                Math.max(reconcileBatchSize, 1)
        );
        if (dueIds.isEmpty()) {
            return;
        }

        List<BlogLikeCount> counts = new ArrayList<>(dueIds.size());
        for (Long blogId : dueIds) {
            Long expected = redisService.prepareReconciliation(blogId);
            if (expected != null) {
                counts.add(new BlogLikeCount(blogId, expected));
            }
        }
        if (counts.isEmpty()) {
            // 正在落库的热点笔记也向后轮转，避免长期占据队首导致其他笔记饥饿。
            redisService.markReconciled(dueIds, now);
            return;
        }

        int repaired = transactionalService.reconcileLikeCounts(counts);
        redisService.markReconciled(dueIds, now);
        if (repaired > 0) {
            log.info("点赞计数对账已修复 {} 篇笔记", repaired);
        }
    }

    @Scheduled(cron = "${hmdp.blog-like.sync-log-cleanup-cron:0 30 3 * * ?}")
    public void cleanExpiredSyncLogs() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(syncLogRetentionDays, 1L));
            // 活跃 processing 批次必须保留幂等记录，否则超长时间 ACK 故障后可能再次累加。
            String activeBatchId = redisService.getActiveProcessingBatchId();
            int deleted = transactionalService.deleteSyncBatchesBefore(cutoff, activeBatchId);
            if (deleted > 0) {
                log.info("已清理 {} 条过期点赞同步批次记录", deleted);
            }
        } catch (Exception e) {
            log.error("清理点赞同步批次记录失败", e);
        }
    }

    private void unlockIfHeld(RLock lock) {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
