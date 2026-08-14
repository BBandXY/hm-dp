package com.hmdp.service.impl;

import com.hmdp.entity.VoucherOrder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Arrays;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/** Redis 预扣库存的幂等修正与补偿。 */
@Component
public class SeckillReservationCompensator {

    private static final String ORDER_SET_KEY = "seckill:order:";
    private static final String DUPLICATE_MARKER_KEY = "seckill:duplicate:restored:";
    private static final String FAILURE_MARKER_KEY = "seckill:failure:compensated:";

    private static final DefaultRedisScript<Long> RESTORE_DUPLICATE_SCRIPT;
    private static final DefaultRedisScript<Long> COMPENSATE_FAILURE_SCRIPT;

    static {
        RESTORE_DUPLICATE_SCRIPT = new DefaultRedisScript<>();
        RESTORE_DUPLICATE_SCRIPT.setLocation(new ClassPathResource("restore_duplicate_reservation.lua"));
        RESTORE_DUPLICATE_SCRIPT.setResultType(Long.class);

        COMPENSATE_FAILURE_SCRIPT = new DefaultRedisScript<>();
        COMPENSATE_FAILURE_SCRIPT.setLocation(new ClassPathResource("compensate_failed_order.lua"));
        COMPENSATE_FAILURE_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;

    public SeckillReservationCompensator(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public Long restoreDuplicateReservation(VoucherOrder order) {
        Long result = stringRedisTemplate.execute(
                RESTORE_DUPLICATE_SCRIPT,
                Arrays.asList(
                        SECKILL_STOCK_KEY + order.getVoucherId(),
                        DUPLICATE_MARKER_KEY + order.getId()
                )
        );
        if (result == null) {
            throw new IllegalStateException("重复订单库存修正脚本未返回结果");
        }
        return result;
    }

    public Long compensateFailedOrder(VoucherOrder order) {
        Long result = stringRedisTemplate.execute(
                COMPENSATE_FAILURE_SCRIPT,
                Arrays.asList(
                        SECKILL_STOCK_KEY + order.getVoucherId(),
                        ORDER_SET_KEY + order.getVoucherId(),
                        FAILURE_MARKER_KEY + order.getId()
                ),
                order.getUserId().toString()
        );
        if (result == null) {
            throw new IllegalStateException("失败订单补偿脚本未返回结果");
        }
        return result;
    }
}
