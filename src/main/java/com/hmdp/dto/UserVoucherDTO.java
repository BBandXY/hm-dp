package com.hmdp.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 用户券列表的聚合视图。 */
@Data
@Accessors(chain = true)
public class UserVoucherDTO {
    private Long id;
    private Long voucherId;
    private String name;
    private String voucherType;
    private Long merchantId;
    private Long thresholdAmount;
    private Long discountAmount;
    private String useRule;
    private String source;
    private String status;
    private LocalDateTime receiveTime;
    private LocalDateTime expireTime;
    private LocalDateTime useTime;
}
