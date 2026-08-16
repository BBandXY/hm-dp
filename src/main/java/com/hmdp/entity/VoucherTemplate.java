package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 营销优惠券模板，和原有秒杀订单券解耦。金额统一以分为单位。 */
@Data
@Accessors(chain = true)
@TableName("voucher_template")
public class VoucherTemplate implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long merchantId;
    private String name;
    private String voucherType;
    private Long thresholdAmount;
    private Long discountAmount;
    private Integer totalStock;
    private Integer remainingStock;
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    private Integer validDays;
    private String receiveRule;
    private String useRule;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
