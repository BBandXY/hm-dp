package com.hmdp.service.marketing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.constants.MarketingConstants;
import com.hmdp.entity.VoucherGrantLog;
import com.hmdp.entity.VoucherTemplate;
import com.hmdp.mapper.UserVoucherMapper;
import com.hmdp.mapper.VoucherGrantLogMapper;
import com.hmdp.mapper.VoucherTemplateMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 将本地发券请求可靠发布到 Redis Stream。
 * Lua 在一个原子操作内完成库存、一人一券、请求幂等和消息入流。
 */
@Slf4j
@Service
public class VoucherGrantPublisher {

    public static final String STREAM_KEY = "stream.voucher.grants";
    public static final String STREAM_GROUP = "voucher-grant-group";
    public static final String DEAD_LETTER_STREAM_KEY = "stream.voucher.grants.dlq";
    public static final String RETRY_HASH_KEY = "stream.voucher.grants:retry";
    private static final String STOCK_KEY_PREFIX = "marketing:voucher:stock:";
    private static final String USER_KEY_PREFIX = "marketing:voucher:users:";
    private static final String REQUEST_KEY_PREFIX = "marketing:voucher:request:";

    private static final DefaultRedisScript<Long> GRANT_SCRIPT;
    private static final DefaultRedisScript<Long> COMPENSATE_SCRIPT;

    static {
        GRANT_SCRIPT = new DefaultRedisScript<>();
        GRANT_SCRIPT.setLocation(new ClassPathResource("voucher_grant.lua"));
        GRANT_SCRIPT.setResultType(Long.class);

        COMPENSATE_SCRIPT = new DefaultRedisScript<>();
        COMPENSATE_SCRIPT.setLocation(new ClassPathResource("voucher_grant_compensate.lua"));
        COMPENSATE_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private VoucherGrantLogMapper voucherGrantLogMapper;

    @Resource
    private VoucherTemplateMapper voucherTemplateMapper;

    @Resource
    private UserVoucherMapper userVoucherMapper;

    @Resource
    private VoucherGrantTransactionalService transactionalService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    public void publish(String requestId) {
        VoucherGrantLog grant = voucherGrantLogMapper.selectOne(
                new LambdaQueryWrapper<VoucherGrantLog>()
                        .eq(VoucherGrantLog::getRequestId, requestId)
                        .last("LIMIT 1")
        );
        if (grant == null || MarketingConstants.GRANT_STATUS_SUCCESS.equals(grant.getStatus())
                || MarketingConstants.GRANT_STATUS_FAILED.equals(grant.getStatus())) {
            return;
        }

        VoucherTemplate template = voucherTemplateMapper.selectById(grant.getVoucherId());
        String invalidReason = validateTemplate(template);
        if (invalidReason != null) {
            transactionalService.markFailed(requestId, invalidReason);
            return;
        }

        long expireAt = calculateExpireAt(template);
        Long result = executeGrantScript(grant, expireAt);
        if (result != null && result == 4L && initializeVoucherCache(template)) {
            result = executeGrantScript(grant, expireAt);
        }
        if (result == null) {
            throw new IllegalStateException("发券 Lua 脚本未返回结果");
        }

        switch (result.intValue()) {
            case 0:
            case 3:
                voucherGrantLogMapper.markPendingIfCreated(requestId);
                return;
            case 1:
                transactionalService.markFailed(requestId, "优惠券库存不足");
                return;
            case 2:
                handleAlreadyReserved(grant);
                return;
            default:
                throw new IllegalStateException("优惠券库存缓存尚未就绪");
        }
    }

    /** 扫描 CREATED 本地消息，补偿事务提交后 Redis 临时不可用的场景。 */
    @Scheduled(fixedDelayString = "${hmdp.marketing.publish-retry-ms:5000}")
    public void retryCreatedRequests() {
        try {
            List<VoucherGrantLog> created = voucherGrantLogMapper.selectList(
                    new LambdaQueryWrapper<VoucherGrantLog>()
                            .eq(VoucherGrantLog::getStatus, MarketingConstants.GRANT_STATUS_CREATED)
                            .orderByAsc(VoucherGrantLog::getId)
                            .last("LIMIT 100")
            );
            for (VoucherGrantLog grant : created) {
                try {
                    publish(grant.getRequestId());
                } catch (Exception e) {
                    log.warn("发券请求发布失败，将由定时任务继续重试。requestId={}", grant.getRequestId(), e);
                }
            }
        } catch (Exception e) {
            log.warn("未发布发券请求扫描失败", e);
        }
    }

    public boolean compensate(String requestId, Long userId, Long voucherId, boolean removeUserReservation) {
        Long result = stringRedisTemplate.execute(
                COMPENSATE_SCRIPT,
                Arrays.asList(
                        STOCK_KEY_PREFIX + voucherId,
                        USER_KEY_PREFIX + voucherId,
                        REQUEST_KEY_PREFIX + requestId
                ),
                userId.toString() + ":" + voucherId,
                userId.toString(),
                removeUserReservation ? "1" : "0"
        );
        return result != null && result == 1L;
    }

    private Long executeGrantScript(VoucherGrantLog grant, long expireAt) {
        return stringRedisTemplate.execute(
                GRANT_SCRIPT,
                Arrays.asList(
                        STOCK_KEY_PREFIX + grant.getVoucherId(),
                        USER_KEY_PREFIX + grant.getVoucherId(),
                        REQUEST_KEY_PREFIX + grant.getRequestId(),
                        STREAM_KEY
                ),
                grant.getRequestId(),
                grant.getUserId().toString(),
                grant.getVoucherId().toString(),
                grant.getTaskProgressId() == null ? "" : grant.getTaskProgressId().toString(),
                grant.getSource(),
                Long.toString(expireAt),
                Long.toString(System.currentTimeMillis())
        );
    }

    private boolean initializeVoucherCache(VoucherTemplate template) {
        RLock lock = redissonClient.getLock("lock:marketing:voucher:init:" + template.getId());
        boolean locked = false;
        try {
            locked = lock.tryLock(1, 10, TimeUnit.SECONDS);
            if (!locked) {
                return false;
            }
            String stockKey = STOCK_KEY_PREFIX + template.getId();
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey))) {
                return true;
            }
            List<Long> owners = userVoucherMapper.selectOwnerIds(template.getId());
            if (!owners.isEmpty()) {
                String[] values = owners.stream().map(String::valueOf).toArray(String[]::new);
                stringRedisTemplate.opsForSet().add(USER_KEY_PREFIX + template.getId(), values);
            }
            // stockKey 最后写入；Lua 看到库存后，用户集合一定已经预热完成。
            stringRedisTemplate.opsForValue().set(
                    stockKey,
                    Integer.toString(Math.max(0, template.getRemainingStock()))
            );
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void handleAlreadyReserved(VoucherGrantLog grant) {
        if (transactionalService.completeAlreadyOwned(grant.getRequestId())) {
            return;
        }
        VoucherGrantLog inFlight = voucherGrantLogMapper.selectOne(
                new LambdaQueryWrapper<VoucherGrantLog>()
                        .eq(VoucherGrantLog::getUserId, grant.getUserId())
                        .eq(VoucherGrantLog::getVoucherId, grant.getVoucherId())
                        .ne(VoucherGrantLog::getRequestId, grant.getRequestId())
                        .in(VoucherGrantLog::getStatus, Arrays.asList(
                                MarketingConstants.GRANT_STATUS_CREATED,
                                MarketingConstants.GRANT_STATUS_PENDING
                        ))
                        .last("LIMIT 1")
        );
        if (inFlight != null) {
            transactionalService.markFailed(grant.getRequestId(), "该优惠券已经领取或正在发放");
            return;
        }
        // Redis 集合可能残留旧成员，数据库没有券时清理后交给可靠发布任务重试。
        stringRedisTemplate.opsForSet().remove(
                USER_KEY_PREFIX + grant.getVoucherId(), grant.getUserId().toString()
        );
        log.warn("清理了无数据库记录的券领取标记，requestId={}", grant.getRequestId());
    }

    private String validateTemplate(VoucherTemplate template) {
        if (template == null || !Integer.valueOf(MarketingConstants.ENABLED).equals(template.getStatus())) {
            return "优惠券不存在或已停用";
        }
        LocalDateTime now = LocalDateTime.now();
        if (template.getBeginTime() != null && now.isBefore(template.getBeginTime())) {
            return "优惠券领取尚未开始";
        }
        if (template.getEndTime() != null && now.isAfter(template.getEndTime())) {
            return "优惠券领取已经结束";
        }
        return null;
    }

    private long calculateExpireAt(VoucherTemplate template) {
        LocalDateTime now = LocalDateTime.now();
        int validDays = template.getValidDays() == null ? 30 : Math.max(template.getValidDays(), 1);
        LocalDateTime expireTime = now.plusDays(validDays);
        if (template.getEndTime() != null && template.getEndTime().isBefore(expireTime)) {
            expireTime = template.getEndTime();
        }
        return expireTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
