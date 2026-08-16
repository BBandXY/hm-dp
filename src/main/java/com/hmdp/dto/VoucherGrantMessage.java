package com.hmdp.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Redis Stream 中的发券消息。 */
@Data
@Accessors(chain = true)
public class VoucherGrantMessage {
    private String requestId;
    private Long userId;
    private Long voucherId;
    private Long taskProgressId;
    private String source;
    private Long expireAt;

    public LocalDateTime expireTime() {
        if (expireAt == null) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(expireAt), ZoneId.systemDefault());
    }
}
