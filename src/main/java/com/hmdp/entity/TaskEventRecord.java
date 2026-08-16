package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 任务事件幂等记录。同一业务对象在同一任务周期只计数一次，防止反复点赞刷任务。
 */
@Data
@Accessors(chain = true)
@TableName("task_event_record")
public class TaskEventRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String taskCode;
    private String bizId;
    private LocalDate taskDate;
    private LocalDateTime createTime;
}
