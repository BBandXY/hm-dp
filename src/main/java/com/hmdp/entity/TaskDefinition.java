package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 每日任务和一次性任务的配置。 */
@Data
@Accessors(chain = true)
@TableName("task_definition")
public class TaskDefinition implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String taskCode;
    private String taskName;
    private String taskType;
    private Integer targetValue;
    private String rewardType;
    private Long rewardId;
    private Integer rewardValue;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
