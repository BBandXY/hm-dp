package com.hmdp.service.marketing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.constants.MarketingConstants;
import com.hmdp.dto.MarketingTaskDTO;
import com.hmdp.entity.TaskDefinition;
import com.hmdp.entity.UserTaskProgress;
import com.hmdp.entity.VoucherGrantLog;
import com.hmdp.mapper.TaskDefinitionMapper;
import com.hmdp.mapper.UserTaskProgressMapper;
import com.hmdp.mapper.VoucherGrantLogMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 组装任务定义、当前进度和异步奖励状态。 */
@Service
public class TaskQueryService {

    @Resource
    private TaskDefinitionMapper taskDefinitionMapper;

    @Resource
    private UserTaskProgressMapper userTaskProgressMapper;

    @Resource
    private VoucherGrantLogMapper voucherGrantLogMapper;

    public List<MarketingTaskDTO> queryTasks(Long userId) {
        List<TaskDefinition> tasks = taskDefinitionMapper.selectList(
                new LambdaQueryWrapper<TaskDefinition>()
                        .eq(TaskDefinition::getStatus, MarketingConstants.ENABLED)
                        .orderByAsc(TaskDefinition::getId)
        );
        if (tasks.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> taskIds = tasks.stream().map(TaskDefinition::getId).collect(Collectors.toSet());
        List<UserTaskProgress> progresses = userTaskProgressMapper.selectList(
                new LambdaQueryWrapper<UserTaskProgress>()
                        .eq(UserTaskProgress::getUserId, userId)
                        .in(UserTaskProgress::getTaskId, taskIds)
                        .in(UserTaskProgress::getTaskDate,
                                Arrays.asList(LocalDate.now(), MarketingConstants.ONCE_TASK_DATE))
        );
        Map<Long, UserTaskProgress> progressByTask = progresses.stream().collect(
                Collectors.toMap(UserTaskProgress::getTaskId, value -> value, (left, right) -> left)
        );

        Set<String> requestIds = progresses.stream()
                .map(UserTaskProgress::getRewardRequestId)
                .filter(value -> value != null && !value.isEmpty())
                .collect(Collectors.toSet());
        Map<String, String> grantStatuses = new HashMap<>();
        if (!requestIds.isEmpty()) {
            voucherGrantLogMapper.selectList(
                    new LambdaQueryWrapper<VoucherGrantLog>()
                            .in(VoucherGrantLog::getRequestId, requestIds)
            ).forEach(log -> grantStatuses.put(log.getRequestId(), log.getStatus()));
        }

        return tasks.stream()
                .map(task -> toDto(task, progressByTask.get(task.getId()), grantStatuses))
                .collect(Collectors.toList());
    }

    private MarketingTaskDTO toDto(TaskDefinition task,
                                   UserTaskProgress progress,
                                   Map<String, String> grantStatuses) {
        String requestId = progress == null ? null : progress.getRewardRequestId();
        String rewardStatus = requestId == null ? null : grantStatuses.get(requestId);
        if (rewardStatus == null && progress != null && Boolean.TRUE.equals(progress.getRewardReceived())) {
            rewardStatus = MarketingConstants.GRANT_STATUS_SUCCESS;
        }
        return new MarketingTaskDTO()
                .setTaskId(task.getId())
                .setTaskCode(task.getTaskCode())
                .setTaskName(task.getTaskName())
                .setTaskType(task.getTaskType())
                .setTargetValue(task.getTargetValue())
                .setProgress(progress == null ? 0 : progress.getProgress())
                .setCompleted(progress != null && Boolean.TRUE.equals(progress.getCompleted()))
                .setRewardType(task.getRewardType())
                .setRewardId(task.getRewardId())
                .setRewardValue(task.getRewardValue())
                .setRewardReceived(progress != null && Boolean.TRUE.equals(progress.getRewardReceived()))
                .setRewardRequestId(requestId)
                .setRewardStatus(rewardStatus);
    }
}
