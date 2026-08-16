package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 发券请求同时充当可靠发布的本地消息记录。 */
@Data
@Accessors(chain = true)
@TableName("voucher_grant_log")
public class VoucherGrantLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String requestId;
    private Long userId;
    private Long voucherId;
    private Long taskProgressId;
    private String source;
    private String status;
    private String failReason;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
