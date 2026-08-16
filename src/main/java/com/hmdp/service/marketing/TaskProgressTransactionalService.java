package com.hmdp.service.marketing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.constants.MarketingConstants;
import com.hmdp.entity.TaskDefinition;
import com.hmdp.entity.UserTaskProgress;
import com.hmdp.mapper.TaskDefinitionMapper;
import com.hmdp.mapper.TaskEventRecordMapper;
import com.hmdp.mapper.UserTaskProgressMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDate;

/** 任务事件去重和 MySQL 进度累加的事务边界。 */
@Service
public class TaskProgressTransactionalService {

    @Resource
    private TaskDefinitionMapper taskDefinitionMapper;

    @Resource
    private TaskEventRecordMapper taskEventRecordMapper;

    @Resource
    private UserTaskProgressMapper userTaskProgressMapper;

    @Transactional
    public ProgressSnapshot recordEvent(Long userId, String taskCode, String bizId, int delta) {
        if (userId == null || taskCode == null || bizId == null || delta <= 0) {
            return null;
        }

        TaskDefinition task = taskDefinitionMapper.selectOne(
                new LambdaQueryWrapper<TaskDefinition>()
                        .eq(TaskDefinition::getTaskCode, taskCode)
                        .eq(TaskDefinition::getStatus, MarketingConstants.ENABLED)
                        .last("LIMIT 1")
        );
        if (task == null || task.getTargetValue() == null || task.getTargetValue() <= 0) {
            return null;
        }

        LocalDate taskDate = taskDate(task.getTaskType());
        int inserted = taskEventRecordMapper.insertIgnore(userId, taskCode, bizId, taskDate);
        if (inserted == 0) {
            return null;
        }

        userTaskProgressMapper.incrementProgress(
                userId, task.getId(), taskDate, delta, task.getTargetValue()
        );
        UserTaskProgress progress = userTaskProgressMapper.selectOne(
                new LambdaQueryWrapper<UserTaskProgress>()
                        .eq(UserTaskProgress::getUserId, userId)
                        .eq(UserTaskProgress::getTaskId, task.getId())
                        .eq(UserTaskProgress::getTaskDate, taskDate)
                        .last("LIMIT 1")
        );
        return progress == null ? null : new ProgressSnapshot(taskDate, progress);
    }

    public static LocalDate taskDate(String taskType) {
        return MarketingConstants.TASK_TYPE_ONCE.equals(taskType)
                ? MarketingConstants.ONCE_TASK_DATE
                : LocalDate.now();
    }

    @Getter
    @AllArgsConstructor
    public static class ProgressSnapshot {
        private final LocalDate taskDate;
        private final UserTaskProgress progress;
    }
}
