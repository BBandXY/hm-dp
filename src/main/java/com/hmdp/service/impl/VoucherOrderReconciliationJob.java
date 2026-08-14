package com.hmdp.service.impl;

import com.hmdp.entity.VoucherOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.ORDER_STATUS_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_PENDING_KEY;

/**
 * 对死信订单执行最终一致性检查：数据库已有订单则修正状态，否则幂等归还 Redis 预扣库存。
 */
@Slf4j
@Component
public class VoucherOrderReconciliationJob {

    private static final long STATUS_TTL_HOURS = 48L;

    private final StringRedisTemplate stringRedisTemplate;
    private final VoucherOrderTransactionalService transactionalService;
    private final SeckillReservationCompensator compensator;

    @Value("${hmdp.seckill.reconcile-grace-ms:60000}")
    private long reconcileGraceMs;

    @Value("${hmdp.seckill.reconcile-batch-size:100}")
    private int batchSize;

    public VoucherOrderReconciliationJob(
            StringRedisTemplate stringRedisTemplate,
            VoucherOrderTransactionalService transactionalService,
            SeckillReservationCompensator compensator
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.transactionalService = transactionalService;
        this.compensator = compensator;
    }

    @Scheduled(
            initialDelayString = "${hmdp.seckill.reconcile-initial-delay-ms:30000}",
            fixedDelayString = "${hmdp.seckill.reconcile-delay-ms:60000}"
    )
    public void reconcileFailedOrders() {
        try {
            double cutoff = System.currentTimeMillis() - reconcileGraceMs;
            Set<String> orderIds = stringRedisTemplate.opsForZSet().rangeByScore(
                    SECKILL_ORDER_PENDING_KEY,
                    0D,
                    cutoff,
                    0L,
                    batchSize
            );
            if (orderIds == null || orderIds.isEmpty()) {
                return;
            }
            for (String orderId : orderIds) {
                reconcileOne(orderId);
            }
        } catch (Exception e) {
            log.error("秒杀订单对账任务执行失败，本轮稍后重试", e);
        }
    }

    private void reconcileOne(String orderIdText) {
        Map<Object, Object> statusMap = stringRedisTemplate.opsForHash().entries(ORDER_STATUS_KEY + orderIdText);
        if (!"FAILED".equals(String.valueOf(statusMap.get("status")))) {
            return;
        }

        Long orderId = parseLong(statusMap.get("orderId"));
        Long userId = parseLong(statusMap.get("userId"));
        Long voucherId = parseLong(statusMap.get("voucherId"));
        if (orderId == null || userId == null || voucherId == null) {
            log.error("对账状态字段不完整，orderId={}", orderIdText);
            return;
        }

        VoucherOrder requestedOrder = new VoucherOrder()
                .setId(orderId)
                .setUserId(userId)
                .setVoucherId(voucherId);
        VoucherOrder persistedOrder = transactionalService.findOrder(orderId, userId, voucherId);
        if (persistedOrder != null) {
            if (!orderId.equals(persistedOrder.getId())) {
                compensator.restoreDuplicateReservation(requestedOrder);
                markStatus(requestedOrder, "DUPLICATE", "数据库中已存在该用户订单", persistedOrder.getId());
            } else {
                markStatus(requestedOrder, "SUCCESS", "数据库订单已确认", persistedOrder.getId());
            }
        } else {
            compensator.compensateFailedOrder(requestedOrder);
            markStatus(requestedOrder, "COMPENSATED", "落库失败，Redis 预扣库存已归还", null);
        }
        stringRedisTemplate.opsForZSet().remove(SECKILL_ORDER_PENDING_KEY, orderIdText);
    }

    private void markStatus(VoucherOrder order, String status, String reason, Long persistedOrderId) {
        Map<String, String> values = new HashMap<>();
        values.put("orderId", order.getId().toString());
        values.put("userId", order.getUserId().toString());
        values.put("voucherId", order.getVoucherId().toString());
        values.put("status", status);
        values.put("reason", reason);
        values.put("updatedAt", Long.toString(System.currentTimeMillis()));
        if (persistedOrderId != null) {
            values.put("persistedOrderId", persistedOrderId.toString());
        }
        String key = ORDER_STATUS_KEY + order.getId();
        stringRedisTemplate.opsForHash().putAll(key, values);
        stringRedisTemplate.expire(key, STATUS_TTL_HOURS, TimeUnit.HOURS);
    }

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
