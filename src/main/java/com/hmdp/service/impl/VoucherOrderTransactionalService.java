package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

/**
 * 秒杀订单数据库事务边界。该 Bean 被 Stream 消费线程直接调用，Spring 事务代理稳定生效。
 */
@Service
public class VoucherOrderTransactionalService {

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private SeckillVoucherMapper seckillVoucherMapper;

    @Transactional
    public PersistResult createVoucherOrder(VoucherOrder order) {
        VoucherOrder sameOrder = voucherOrderMapper.selectById(order.getId());
        if (sameOrder != null) {
            return PersistResult.idempotent(sameOrder.getId());
        }

        VoucherOrder existingOrder = voucherOrderMapper.selectOne(
                new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getUserId, order.getUserId())
                        .eq(VoucherOrder::getVoucherId, order.getVoucherId())
                        .last("LIMIT 1")
        );
        if (existingOrder != null) {
            return PersistResult.duplicate(existingOrder.getId());
        }

        int updated = seckillVoucherMapper.update(
                null,
                new LambdaUpdateWrapper<SeckillVoucher>()
                        .setSql("stock = stock - 1")
                        .eq(SeckillVoucher::getVoucherId, order.getVoucherId())
                        .gt(SeckillVoucher::getStock, 0)
        );
        if (updated == 0) {
            throw new IllegalStateException("数据库库存不足或秒杀券不存在");
        }

        voucherOrderMapper.insert(order);
        return PersistResult.created(order.getId());
    }

    public VoucherOrder findOrder(Long orderId, Long userId, Long voucherId) {
        VoucherOrder order = voucherOrderMapper.selectById(orderId);
        if (order != null) {
            return order;
        }
        if (userId == null || voucherId == null) {
            return null;
        }
        return voucherOrderMapper.selectOne(
                new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getUserId, userId)
                        .eq(VoucherOrder::getVoucherId, voucherId)
                        .last("LIMIT 1")
        );
    }

    public enum PersistStatus {
        CREATED,
        IDEMPOTENT,
        DUPLICATE
    }

    public static final class PersistResult {
        private final PersistStatus status;
        private final Long existingOrderId;

        private PersistResult(PersistStatus status, Long existingOrderId) {
            this.status = status;
            this.existingOrderId = existingOrderId;
        }

        public static PersistResult created(Long orderId) {
            return new PersistResult(PersistStatus.CREATED, orderId);
        }

        public static PersistResult idempotent(Long orderId) {
            return new PersistResult(PersistStatus.IDEMPOTENT, orderId);
        }

        public static PersistResult duplicate(Long orderId) {
            return new PersistResult(PersistStatus.DUPLICATE, orderId);
        }

        public PersistStatus getStatus() {
            return status;
        }

        public Long getExistingOrderId() {
            return existingOrderId;
        }
    }
}
