package com.hmdp.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 任务中心展示模型。 */
@Data
@Accessors(chain = true)
public class MarketingTaskDTO {
    private Long taskId;
    private String taskCode;
    private String taskName;
    private String taskType;
    private Integer targetValue;
    private Integer progress;
    private Boolean completed;
    private String rewardType;
    private Long rewardId;
    private Integer rewardValue;
    private Boolean rewardReceived;
    private String rewardRequestId;
    private String rewardStatus;
}
