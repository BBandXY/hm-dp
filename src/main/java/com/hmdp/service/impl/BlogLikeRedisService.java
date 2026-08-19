package com.hmdp.service.impl;

import com.hmdp.dto.BlogLikeDelta;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_BASELINE_KEY;
import static com.hmdp.utils.RedisConstants.BLOG_LIKED_COUNT_KEY;
import static com.hmdp.utils.RedisConstants.BLOG_LIKED_DELTA_KEY;
import static com.hmdp.utils.RedisConstants.BLOG_LIKED_DIRTY_KEY;
import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.BLOG_LIKED_PROCESSING_BATCH_KEY;
import static com.hmdp.utils.RedisConstants.BLOG_LIKED_PROCESSING_KEY;
import static com.hmdp.utils.RedisConstants.BLOG_LIKED_RECONCILE_KEY;

/**
 * 点赞 Redis 数据访问层。
 *
 * <p>所有涉及多个 Key 的状态变化都封装在 Lua 中，业务服务只处理明确的结果，
 * 避免把原子性细节散落在控制流程里。</p>
 */
@Component
public class BlogLikeRedisService {

    private static final DefaultRedisScript<Long> TOGGLE_LIKE_SCRIPT = longScript("blog_like.lua");
    private static final DefaultRedisScript<String> CLAIM_DELTA_SCRIPT = stringScript("claim_blog_like_delta.lua");
    private static final DefaultRedisScript<List> READ_DELTA_SCRIPT = listScript("read_blog_like_delta.lua");
    private static final DefaultRedisScript<Long> ACK_DELTA_SCRIPT = longScript("ack_blog_like_delta.lua");
    private static final DefaultRedisScript<Long> RECONCILE_SCRIPT = longScript("reconcile_blog_like.lua");

    private final StringRedisTemplate stringRedisTemplate;

    public BlogLikeRedisService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 原子切换用户点赞状态。
     *
     * @param initialLiked Redis 冷数据时使用的数据库初值；传 null 时脚本会返回 NEEDS_INITIALIZATION
     */
    public ToggleResult toggleLike(Long blogId, Long userId, Integer initialLiked) {
        String seed = initialLiked == null ? "-1" : Integer.toString(Math.max(initialLiked, 0));
        Long result = stringRedisTemplate.execute(
                TOGGLE_LIKE_SCRIPT,
                Arrays.asList(
                        BLOG_LIKED_KEY + blogId,
                        BLOG_LIKED_DELTA_KEY,
                        BLOG_LIKED_DIRTY_KEY,
                        BLOG_LIKED_COUNT_KEY,
                        BLOG_LIKED_BASELINE_KEY,
                        BLOG_LIKED_RECONCILE_KEY
                ),
                userId.toString(),
                blogId.toString(),
                Long.toString(System.currentTimeMillis()),
                seed
        );
        if (result == null) {
            throw new IllegalStateException("点赞状态更新失败，请稍后重试");
        }
        if (result == 0L) {
            return ToggleResult.NEEDS_INITIALIZATION;
        }
        return result > 0L ? ToggleResult.LIKED : ToggleResult.UNLIKED;
    }

    /** Redis 已初始化时返回实时逻辑点赞数，否则返回 null。 */
    public Long getLogicalLikeCount(Long blogId) {
        Object value = stringRedisTemplate.opsForHash().get(BLOG_LIKED_COUNT_KEY, blogId.toString());
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Redis 点赞计数格式错误，blogId=" + blogId, e);
        }
    }

    /**
     * 领取一个有限大小的增量快照。若已有未确认快照，则返回原批次号以便失败重试。
     */
    public String claimPendingDeltas(String requestedBatchId, int batchSize) {
        String batchId = stringRedisTemplate.execute(
                CLAIM_DELTA_SCRIPT,
                Arrays.asList(
                        BLOG_LIKED_DELTA_KEY,
                        BLOG_LIKED_DIRTY_KEY,
                        BLOG_LIKED_PROCESSING_KEY,
                        BLOG_LIKED_PROCESSING_BATCH_KEY
                ),
                requestedBatchId,
                Integer.toString(batchSize)
        );
        return StringUtils.hasText(batchId) ? batchId : null;
    }

    /** 原子校验批次号并读取快照，防止旧消费者误读后继批次。 */
    @SuppressWarnings("unchecked")
    public List<BlogLikeDelta> readProcessingDeltas(String batchId) {
        List<Object> values = stringRedisTemplate.execute(
                READ_DELTA_SCRIPT,
                Arrays.asList(BLOG_LIKED_PROCESSING_BATCH_KEY, BLOG_LIKED_PROCESSING_KEY),
                batchId
        );
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        if (values.size() % 2 != 0) {
            throw new IllegalStateException("Redis 点赞增量快照字段数量异常，batchId=" + batchId);
        }

        List<BlogLikeDelta> deltas = new ArrayList<>(values.size() / 2);
        for (int i = 0; i < values.size(); i += 2) {
            try {
                Long blogId = Long.valueOf(String.valueOf(values.get(i)));
                Long delta = Long.valueOf(String.valueOf(values.get(i + 1)));
                if (delta != 0L) {
                    deltas.add(new BlogLikeDelta(blogId, delta));
                }
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Redis 点赞增量格式错误，batchId=" + batchId, e);
            }
        }
        deltas.sort(Comparator.comparing(BlogLikeDelta::getBlogId));
        return deltas;
    }

    /** 仅当批次号仍匹配时确认并删除 processing 快照。 */
    public boolean acknowledgeDeltaBatch(String batchId) {
        Long acknowledged = stringRedisTemplate.execute(
                ACK_DELTA_SCRIPT,
                Arrays.asList(BLOG_LIKED_PROCESSING_KEY, BLOG_LIKED_PROCESSING_BATCH_KEY),
                batchId
        );
        return Long.valueOf(1L).equals(acknowledged);
    }

    public String getActiveProcessingBatchId() {
        return stringRedisTemplate.opsForValue().get(BLOG_LIKED_PROCESSING_BATCH_KEY);
    }

    public List<Long> findDueReconciliationIds(long cutoffTime, int batchSize) {
        Set<String> ids = stringRedisTemplate.opsForZSet().rangeByScore(
                BLOG_LIKED_RECONCILE_KEY,
                0D,
                cutoffTime,
                0L,
                batchSize
        );
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> blogIds = new ArrayList<>(ids.size());
        for (String id : ids) {
            try {
                blogIds.add(Long.valueOf(id));
            } catch (NumberFormatException ignored) {
                // 该队列只应由 Lua 写入数字 blogId；删除坏成员，避免它长期占用对账批次。
                stringRedisTemplate.opsForZSet().remove(BLOG_LIKED_RECONCILE_KEY, id);
            }
        }
        return blogIds;
    }

    /**
     * 获取可安全对账的绝对值。存在 pending/processing 增量时返回 null，避免重复叠加。
     */
    public Long prepareReconciliation(Long blogId) {
        Long expected = stringRedisTemplate.execute(
                RECONCILE_SCRIPT,
                Arrays.asList(
                        BLOG_LIKED_KEY + blogId,
                        BLOG_LIKED_DELTA_KEY,
                        BLOG_LIKED_PROCESSING_KEY,
                        BLOG_LIKED_BASELINE_KEY,
                        BLOG_LIKED_COUNT_KEY
                ),
                blogId.toString()
        );
        return expected == null || expected < 0L ? null : expected;
    }

    public void markReconciled(List<Long> blogIds, long reconciledAt) {
        for (Long blogId : blogIds) {
            stringRedisTemplate.opsForZSet().add(
                    BLOG_LIKED_RECONCILE_KEY,
                    blogId.toString(),
                    reconciledAt
            );
        }
    }

    private static DefaultRedisScript<Long> longScript(String resource) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(resource));
        script.setResultType(Long.class);
        return script;
    }

    private static DefaultRedisScript<String> stringScript(String resource) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(resource));
        script.setResultType(String.class);
        return script;
    }

    @SuppressWarnings("rawtypes")
    private static DefaultRedisScript<List> listScript(String resource) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(resource));
        script.setResultType(List.class);
        return script;
    }

    public enum ToggleResult {
        NEEDS_INITIALIZATION,
        LIKED,
        UNLIKED
    }
}
