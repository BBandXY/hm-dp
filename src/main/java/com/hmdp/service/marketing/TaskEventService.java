package com.hmdp.service.marketing;

import com.hmdp.entity.UserTaskProgress;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * 核心业务调用的任务事件门面。
 *
 * <p>所有异常都在这里降级，签到、登录和笔记操作不会因为营销系统故障而失败。</p>
 */
@Slf4j
@Service
public class TaskEventService {

    private static final String TASK_PROGRESS_KEY_PREFIX = "marketing:task:progress:";

    @Resource
    private TaskProgressTransactionalService transactionalService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void recordSafely(Long userId, String taskCode, String bizId) {
        recordSafely(userId, taskCode, bizId, 1);
    }

    public void recordSafely(Long userId, String taskCode, String bizId, int delta) {
        try {
            TaskProgressTransactionalService.ProgressSnapshot snapshot =
                    transactionalService.recordEvent(userId, taskCode, bizId, delta);
            if (snapshot != null) {
                cacheProgress(userId, snapshot);
            }
        } catch (Exception e) {
            log.warn("营销任务事件处理失败，已降级。userId={}, taskCode={}, bizId={}",
                    userId, taskCode, bizId, e);
        }
    }

    private void cacheProgress(Long userId, TaskProgressTransactionalService.ProgressSnapshot snapshot) {
        try {
            UserTaskProgress progress = snapshot.getProgress();
            String key = TASK_PROGRESS_KEY_PREFIX + userId + ":" + snapshot.getTaskDate();
            stringRedisTemplate.opsForHash().put(key, progress.getTaskId().toString(), progress.getProgress().toString());
            stringRedisTemplate.expire(key, 3, TimeUnit.DAYS);
        } catch (Exception e) {
            // MySQL 是进度事实源，Redis 仅用于任务中心的热点进度缓存。
            log.debug("任务进度缓存写入失败，忽略本次缓存更新。userId={}", userId, e);
        }
    }
}
