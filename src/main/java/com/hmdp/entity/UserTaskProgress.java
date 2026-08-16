package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 用户在一个任务周期内的进度和奖励领取状态。 */
@Data
@Accessors(chain = true)
@TableName("user_task_progress")
public class UserTaskProgress implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long taskId;
    private Integer progress;
    private LocalDate taskDate;
    private Boolean completed;
    private Boolean rewardReceived;
    /** 券奖励异步发放时用它关联请求；非空表示奖励已进入处理流程。 */
    private String rewardRequestId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
