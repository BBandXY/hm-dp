package com.hmdp.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 领奖结果。券奖励通常先返回 PENDING，再由 Stream 消费者异步落库。 */
@Data
@Accessors(chain = true)
public class RewardClaimDTO {
    private String requestId;
    private String rewardType;
    private String status;
    private Integer points;
    private Long voucherId;
    private String message;
}
