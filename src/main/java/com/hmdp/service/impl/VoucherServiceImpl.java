package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.time.ZoneId;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_BEGIN_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_END_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        if (voucher.getStock() == null || voucher.getStock() < 0
                || voucher.getBeginTime() == null || voucher.getEndTime() == null
                || !voucher.getBeginTime().isBefore(voucher.getEndTime())) {
            throw new IllegalArgumentException("秒杀库存或活动时间配置不合法");
        }
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);

        // 数据库事务提交后再刷新 Redis；即使刷新失败，请求入口也可以按需从数据库恢复缓存。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    stringRedisTemplate.opsForValue().set(
                            SECKILL_STOCK_KEY + voucher.getId(),
                            voucher.getStock().toString()
                    );
                    stringRedisTemplate.opsForValue().set(
                            SECKILL_BEGIN_KEY + voucher.getId(),
                            Long.toString(voucher.getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                    );
                    stringRedisTemplate.opsForValue().set(
                            SECKILL_END_KEY + voucher.getId(),
                            Long.toString(voucher.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                    );
                } catch (Exception e) {
                    log.error("秒杀券 Redis 缓存初始化失败，voucherId={}", voucher.getId(), e);
                }
            }
        });
    }
}
