package com.hmdp.service.marketing;

import com.hmdp.constants.MarketingConstants;
import com.hmdp.entity.TaskDefinition;
import com.hmdp.entity.UserTaskProgress;
import com.hmdp.mapper.TaskDefinitionMapper;
import com.hmdp.mapper.TaskEventRecordMapper;
import com.hmdp.mapper.UserTaskProgressMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskProgressTransactionalServiceTest {

    @Mock
    private TaskDefinitionMapper taskDefinitionMapper;

    @Mock
    private TaskEventRecordMapper taskEventRecordMapper;

    @Mock
    private UserTaskProgressMapper userTaskProgressMapper;

    @InjectMocks
    private TaskProgressTransactionalService service;

    @Test
    void shouldIgnoreDuplicatedBusinessEvent() {
        TaskDefinition task = dailyTask();
        when(taskDefinitionMapper.selectOne(any())).thenReturn(task);
        when(taskEventRecordMapper.insertIgnore(
                eq(10L), eq(MarketingConstants.TASK_LIKE_BLOG), eq("blog:20"), any(LocalDate.class)
        )).thenReturn(0);

        TaskProgressTransactionalService.ProgressSnapshot result = service.recordEvent(
                10L, MarketingConstants.TASK_LIKE_BLOG, "blog:20", 1
        );

        assertNull(result);
        verify(userTaskProgressMapper, never()).incrementProgress(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void shouldIncrementProgressAfterEventWasAccepted() {
        TaskDefinition task = dailyTask();
        UserTaskProgress progress = new UserTaskProgress()
                .setId(99L)
                .setUserId(10L)
                .setTaskId(task.getId())
                .setTaskDate(LocalDate.now())
                .setProgress(2)
                .setCompleted(false);
        when(taskDefinitionMapper.selectOne(any())).thenReturn(task);
        when(taskEventRecordMapper.insertIgnore(
                eq(10L), eq(MarketingConstants.TASK_LIKE_BLOG), eq("blog:21"), any(LocalDate.class)
        )).thenReturn(1);
        when(userTaskProgressMapper.selectOne(any())).thenReturn(progress);

        TaskProgressTransactionalService.ProgressSnapshot result = service.recordEvent(
                10L, MarketingConstants.TASK_LIKE_BLOG, "blog:21", 1
        );

        assertSame(progress, result.getProgress());
        assertEquals(LocalDate.now(), result.getTaskDate());
        verify(userTaskProgressMapper).incrementProgress(10L, task.getId(), LocalDate.now(), 1, 3);
    }

    private TaskDefinition dailyTask() {
        return new TaskDefinition()
                .setId(104L)
                .setTaskCode(MarketingConstants.TASK_LIKE_BLOG)
                .setTaskType(MarketingConstants.TASK_TYPE_DAILY)
                .setTargetValue(3)
                .setStatus(MarketingConstants.ENABLED);
    }
}
