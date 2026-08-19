package com.hmdp.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * “我的优惠券”页面需要展示的秒杀订单信息。
 *
 * <p>秒杀券仍然归属于原有的订单模型，本 DTO 只负责查询和展示，
 * 不会把 {@code tb_voucher} 的主键写入营销券表，避免两套券模型互相污染。</p>
 */
@Data
@Accessors(chain = true)
public class SeckillVoucherOrderDTO {
    private Long orderId;
    private Long voucherId;
    private Long shopId;
    private String name;
    private String useRule;
    private Long payAmount;
    private Long actualAmount;
    private Integer orderStatus;
    private LocalDateTime createTime;
    private LocalDateTime activityEndTime;
    private LocalDateTime useTime;
}
