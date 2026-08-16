package com.hmdp.service.marketing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;

/** Redis 固定窗口限流；Redis 故障时 fail-open，避免营销模块扩大故障面。 */
@Slf4j
@Component
public class MarketingRateLimiter {

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setLocation(new ClassPathResource("fixed_window_rate_limit.lua"));
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public boolean allowRewardClaim(Long userId) {
        try {
            String key = "marketing:rate:reward:" + userId + ":" + (System.currentTimeMillis() / 1000);
            Long result = stringRedisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    Collections.singletonList(key),
                    "5", "2"
            );
            return result == null || result == 1L;
        } catch (Exception e) {
            log.warn("营销领奖限流器不可用，本次请求降级放行。userId={}", userId, e);
            return true;
        }
    }
}
