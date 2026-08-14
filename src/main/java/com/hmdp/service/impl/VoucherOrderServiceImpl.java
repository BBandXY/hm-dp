package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.ORDER_STATUS_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_BEGIN_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_END_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_PENDING_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * 秒杀请求入口和 Redis Stream 消费者。
 * 数据库事务由 {@link VoucherOrderTransactionalService} 独立承担，避免在线程中获取 AOP 代理。
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    private static final String STREAM_KEY = "stream.orders";
    private static final String STREAM_GROUP = "g1";
    private static final String DEAD_LETTER_STREAM_KEY = "stream.orders.dlq";
    private static final String RETRY_HASH_KEY = "stream.orders:retry";
    private static final long ORDER_STATUS_TTL_HOURS = 48L;
    private static final int PENDING_BATCH_SIZE = 10;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private VoucherOrderTransactionalService transactionalService;

    @Resource
    private SeckillReservationCompensator reservationCompensator;

    @Value("${hmdp.seckill.consumer-name:c1}")
    private String consumerName;

    @Value("${hmdp.seckill.max-retries:5}")
    private int maxRetries;

    private final ExecutorService orderExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "voucher-order-consumer");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean running;
    private volatile boolean streamGroupReady;

    @PostConstruct
    public void init() {
        running = true;
        try {
            ensureStreamGroup();
        } catch (Exception e) {
            log.warn("Redis Stream 消费组初始化失败，消费者线程将继续重试", e);
        }
        orderExecutor.submit(this::consumeOrders);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        orderExecutor.shutdownNow();
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        if (voucherId == null || UserHolder.getUser() == null) {
            return Result.fail("请求参数或登录状态无效");
        }

        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        Long result = executeSeckillScript(voucherId, userId, orderId);

        // 老数据第一次访问时可能尚未写入活动时间缓存，按需从数据库初始化后重试一次。
        if (result != null && result == 5L && loadVoucherCache(voucherId)) {
            result = executeSeckillScript(voucherId, userId, orderId);
        }
        if (result == null) {
            log.error("秒杀脚本未返回结果，voucherId={}, userId={}", voucherId, userId);
            return Result.fail("系统繁忙，请稍后重试");
        }

        int code = result.intValue();
        if (code != 0) {
            switch (code) {
                case 1:
                    return Result.fail("库存不足");
                case 2:
                    return Result.fail("不能重复下单");
                case 3:
                    return Result.fail("秒杀尚未开始");
                case 4:
                    return Result.fail("秒杀已经结束");
                default:
                    return Result.fail("秒杀活动不存在或配置不完整");
            }
        }
        return Result.ok(orderId);
    }

    @Override
    public Result queryOrderStatus(Long orderId) {
        if (orderId == null || UserHolder.getUser() == null) {
            return Result.fail("订单不存在");
        }
        Map<Object, Object> status = stringRedisTemplate.opsForHash().entries(ORDER_STATUS_KEY + orderId);
        if (!status.isEmpty()) {
            Object owner = status.get("userId");
            if (owner == null || !UserHolder.getUser().getId().toString().equals(owner.toString())) {
                return Result.fail("无权查看该订单");
            }
            return Result.ok(status);
        }

        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("订单结果尚未生成或已经过期");
        }
        if (!UserHolder.getUser().getId().equals(order.getUserId())) {
            return Result.fail("无权查看该订单");
        }
        Map<String, String> persisted = new HashMap<>();
        persisted.put("orderId", orderId.toString());
        persisted.put("userId", order.getUserId().toString());
        persisted.put("voucherId", order.getVoucherId().toString());
        persisted.put("status", "SUCCESS");
        return Result.ok(persisted);
    }

    private Long executeSeckillScript(Long voucherId, Long userId, long orderId) {
        return stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                Long.toString(orderId),
                Long.toString(System.currentTimeMillis())
        );
    }

    private boolean loadVoucherCache(Long voucherId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null || voucher.getBeginTime() == null || voucher.getEndTime() == null) {
            return false;
        }
        stringRedisTemplate.opsForValue().setIfAbsent(
                SECKILL_STOCK_KEY + voucherId,
                voucher.getStock().toString()
        );
        stringRedisTemplate.opsForValue().set(
                SECKILL_BEGIN_KEY + voucherId,
                Long.toString(voucher.getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        );
        stringRedisTemplate.opsForValue().set(
                SECKILL_END_KEY + voucherId,
                Long.toString(voucher.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        );
        return true;
    }

    private void consumeOrders() {
        long lastPendingCheck = 0L;
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                ensureStreamGroup();
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(STREAM_GROUP, consumerName),
                        StreamReadOptions.empty().count(10).block(Duration.ofSeconds(2)),
                        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                );
                if (records != null) {
                    for (MapRecord<String, Object, Object> record : records) {
                        processRecord(record);
                    }
                }

                long now = System.currentTimeMillis();
                if (now - lastPendingCheck >= 5000L) {
                    recoverPendingMessages();
                    lastPendingCheck = now;
                }
            } catch (Exception e) {
                streamGroupReady = false;
                log.error("秒杀订单消费者循环异常，将继续重试", e);
                sleepQuietly(1000L);
            }
        }
    }

    private void recoverPendingMessages() {
        try {
            List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                    Consumer.from(STREAM_GROUP, consumerName),
                    StreamReadOptions.empty().count(PENDING_BATCH_SIZE),
                    StreamOffset.create(STREAM_KEY, ReadOffset.from("0"))
            );
            if (records == null || records.isEmpty()) {
                return;
            }
            for (MapRecord<String, Object, Object> record : records) {
                processRecord(record);
            }
        } catch (Exception e) {
            log.error("处理 Pending List 异常，本轮恢复结束", e);
        }
    }

    private void processRecord(MapRecord<String, Object, Object> record) {
        String messageId = record.getId().getValue();
        try {
            if ("init".equals(String.valueOf(record.getValue().get("type")))) {
                acknowledge(record.getId());
                return;
            }
            VoucherOrder order = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
            validateOrderMessage(order, messageId);
            VoucherOrderTransactionalService.PersistResult result = persistWithUserLock(order);

            if (result.getStatus() == VoucherOrderTransactionalService.PersistStatus.DUPLICATE) {
                restoreDuplicateReservation(order);
                markOrderStatus(order, "DUPLICATE", "用户已存在订单", result.getExistingOrderId());
            } else {
                markOrderStatus(order, "SUCCESS", null, order.getId());
            }
            stringRedisTemplate.opsForZSet().remove(SECKILL_ORDER_PENDING_KEY, order.getId().toString());
            stringRedisTemplate.opsForHash().delete(RETRY_HASH_KEY, messageId);
            acknowledge(record.getId());
        } catch (Exception e) {
            handleRecordFailure(record, e);
        }
    }

    private VoucherOrderTransactionalService.PersistResult persistWithUserLock(VoucherOrder order)
            throws InterruptedException {
        String lockKey = "lock:order:" + order.getVoucherId() + ":" + order.getUserId();
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = lock.tryLock(0L, 15L, TimeUnit.SECONDS);
        if (!locked) {
            throw new IllegalStateException("订单正在处理中，稍后重试");
        }
        try {
            return transactionalService.createVoucherOrder(order);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void validateOrderMessage(VoucherOrder order, String messageId) {
        if (order.getId() == null || order.getUserId() == null || order.getVoucherId() == null) {
            throw new IllegalArgumentException("订单消息字段不完整，messageId=" + messageId);
        }
    }

    private void handleRecordFailure(MapRecord<String, Object, Object> record, Exception cause) {
        String messageId = record.getId().getValue();
        Long retries = stringRedisTemplate.opsForHash().increment(RETRY_HASH_KEY, messageId, 1L);
        stringRedisTemplate.expire(RETRY_HASH_KEY, 7L, TimeUnit.DAYS);
        long retryCount = retries == null ? 1L : retries;
        log.error("秒杀订单消息处理失败，messageId={}, retry={}/{}", messageId, retryCount, maxRetries, cause);

        if (retryCount < maxRetries) {
            return;
        }

        Map<String, String> deadLetter = new HashMap<>();
        for (Map.Entry<Object, Object> entry : record.getValue().entrySet()) {
            deadLetter.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        deadLetter.put("originalMessageId", messageId);
        deadLetter.put("retryCount", Long.toString(retryCount));
        deadLetter.put("failedAt", Long.toString(System.currentTimeMillis()));
        deadLetter.put("failureReason", StrUtil.subWithLength(String.valueOf(cause.getMessage()), 0, 500));

        RecordId dlqId = stringRedisTemplate.opsForStream().add(
                StreamRecords.newRecord().in(DEAD_LETTER_STREAM_KEY).ofMap(deadLetter)
        );
        if (dlqId == null) {
            log.error("死信写入失败，保留原消息不 ACK，messageId={}", messageId);
            return;
        }

        VoucherOrder order = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
        if (order.getId() != null) {
            markOrderStatus(order, "FAILED", "订单处理失败，等待对账补偿", null);
        }
        acknowledge(record.getId());
        stringRedisTemplate.opsForHash().delete(RETRY_HASH_KEY, messageId);
        log.error("消息已转入死信流，messageId={}, dlqId={}", messageId, dlqId.getValue());
    }

    private void restoreDuplicateReservation(VoucherOrder order) {
        reservationCompensator.restoreDuplicateReservation(order);
    }

    private void markOrderStatus(
            VoucherOrder order,
            String status,
            String reason,
            Long persistedOrderId
    ) {
        String key = ORDER_STATUS_KEY + order.getId();
        Map<String, String> values = new HashMap<>();
        values.put("orderId", order.getId().toString());
        values.put("userId", order.getUserId().toString());
        values.put("voucherId", order.getVoucherId().toString());
        values.put("status", status);
        values.put("updatedAt", Long.toString(System.currentTimeMillis()));
        if (reason != null) {
            values.put("reason", reason);
        }
        if (persistedOrderId != null) {
            values.put("persistedOrderId", persistedOrderId.toString());
        }
        stringRedisTemplate.opsForHash().putAll(key, values);
        stringRedisTemplate.expire(key, ORDER_STATUS_TTL_HOURS, TimeUnit.HOURS);
    }

    private void acknowledge(RecordId recordId) {
        Long acknowledged = stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY, STREAM_GROUP, recordId);
        if (acknowledged == null || acknowledged == 0L) {
            log.warn("消息 ACK 未生效，messageId={}", recordId.getValue());
        }
    }

    private void ensureStreamGroup() {
        if (streamGroupReady) {
            return;
        }
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(STREAM_KEY))) {
            Map<String, String> initializer = Collections.singletonMap("type", "init");
            stringRedisTemplate.opsForStream().add(STREAM_KEY, initializer);
        }
        try {
            stringRedisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0-0"), STREAM_GROUP);
            log.info("Redis Stream 消费组创建成功，stream={}, group={}", STREAM_KEY, STREAM_GROUP);
            streamGroupReady = true;
        } catch (RuntimeException e) {
            if (!containsMessage(e, "BUSYGROUP")) {
                throw e;
            }
            streamGroupReady = true;
        }
    }

    private boolean containsMessage(Throwable error, String text) {
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(text)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
