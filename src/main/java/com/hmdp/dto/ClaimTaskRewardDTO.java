package com.hmdp.dto;

import lombok.Data;

/** 客户端生成 requestId；网络重试时必须复用同一个值。 */
@Data
public class ClaimTaskRewardDTO {
    private String requestId;
}
