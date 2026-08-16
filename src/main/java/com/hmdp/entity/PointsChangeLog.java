package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 积分变动流水，用 request_id 做最终幂等。 */
@Data
@Accessors(chain = true)
@TableName("points_change_log")
public class PointsChangeLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String requestId;
    private Long userId;
    private Long taskProgressId;
    private Integer points;
    private String source;
    private LocalDateTime createTime;
}
