package com.hmdp.service.marketing;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.VoucherGrantMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 营销券 Stream 消费者：事务落库、有限重试、死信和库存补偿。 */
@Slf4j
@Component
public class VoucherGrantConsumer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private VoucherGrantTransactionalService transactionalService;

    @Resource
    private VoucherGrantPublisher publisher;

    @Value("${hmdp.marketing.consumer-name:marketing-c1}")
    private String consumerName;

    @Value("${hmdp.marketing.max-retries:5}")
    private int maxRetries;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "voucher-grant-consumer");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean running;
    private volatile boolean streamGroupReady;

    @PostConstruct
    public void init() {
        running = true;
        executor.submit(this::consume);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        executor.shutdownNow();
    }

    private void consume() {
        long lastPendingCheck = 0L;
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                ensureStreamGroup();
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(VoucherGrantPublisher.STREAM_GROUP, consumerName),
                        StreamReadOptions.empty().count(10).block(Duration.ofSeconds(2)),
                        StreamOffset.create(VoucherGrantPublisher.STREAM_KEY, ReadOffset.lastConsumed())
                );
                processRecords(records);

                long now = System.currentTimeMillis();
                if (now - lastPendingCheck >= 5000L) {
                    recoverPending();
                    lastPendingCheck = now;
                }
            } catch (Exception e) {
                streamGroupReady = false;
                log.error("营销券消费者循环异常，将继续重试", e);
                sleepQuietly(1000L);
            }
        }
    }

    private void recoverPending() {
        List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                Consumer.from(VoucherGrantPublisher.STREAM_GROUP, consumerName),
                StreamReadOptions.empty().count(10),
                StreamOffset.create(VoucherGrantPublisher.STREAM_KEY, ReadOffset.from("0"))
        );
        processRecords(records);
    }

    private void processRecords(List<MapRecord<String, Object, Object>> records) {
        if (records == null) {
            return;
        }
        for (MapRecord<String, Object, Object> record : records) {
            processRecord(record);
        }
    }

    private void processRecord(MapRecord<String, Object, Object> record) {
        try {
            if ("init".equals(String.valueOf(record.getValue().get("type")))) {
                acknowledge(record.getId());
                return;
            }
            VoucherGrantMessage message = BeanUtil.fillBeanWithMap(
                    record.getValue(), new VoucherGrantMessage(), true
            );
            validate(message);
            VoucherGrantTransactionalService.PersistStatus status = transactionalService.persistGrant(message);
            if (status == VoucherGrantTransactionalService.PersistStatus.DUPLICATE) {
                publisher.compensate(
                        message.getRequestId(), message.getUserId(), message.getVoucherId(), false
                );
            } else if (status == VoucherGrantTransactionalService.PersistStatus.FAILED) {
                publisher.compensate(
                        message.getRequestId(), message.getUserId(), message.getVoucherId(), true
                );
            }
            clearRetry(record.getId());
            acknowledge(record.getId());
        } catch (Exception e) {
            handleFailure(record, e);
        }
    }

    private void handleFailure(MapRecord<String, Object, Object> record, Exception cause) {
        String messageId = record.getId().getValue();
        Long retries = stringRedisTemplate.opsForHash().increment(
                VoucherGrantPublisher.RETRY_HASH_KEY, messageId, 1L
        );
        stringRedisTemplate.expire(VoucherGrantPublisher.RETRY_HASH_KEY, 7, TimeUnit.DAYS);
        long retryCount = retries == null ? 1L : retries;
        log.error("营销发券消息处理失败，messageId={}, retry={}/{}",
                messageId, retryCount, maxRetries, cause);
        if (retryCount < maxRetries) {
            return;
        }

        Map<String, String> deadLetter = new HashMap<>();
        record.getValue().forEach((key, value) -> deadLetter.put(String.valueOf(key), String.valueOf(value)));
        deadLetter.put("originalMessageId", messageId);
        deadLetter.put("failureReason", StrUtil.subWithLength(String.valueOf(cause.getMessage()), 0, 500));
        RecordId dlqId = stringRedisTemplate.opsForStream().add(
                StreamRecords.newRecord().in(VoucherGrantPublisher.DEAD_LETTER_STREAM_KEY).ofMap(deadLetter)
        );
        if (dlqId == null) {
            return;
        }

        VoucherGrantMessage message = BeanUtil.fillBeanWithMap(
                record.getValue(), new VoucherGrantMessage(), true
        );
        if (message.getRequestId() != null && message.getUserId() != null
                && message.getVoucherId() != null) {
            transactionalService.markFailed(message.getRequestId(), cause.getMessage());
            publisher.compensate(message.getRequestId(), message.getUserId(), message.getVoucherId(), true);
        } else if (message.getRequestId() != null) {
            transactionalService.markFailed(message.getRequestId(), cause.getMessage());
        }
        clearRetry(record.getId());
        acknowledge(record.getId());
    }

    private void validate(VoucherGrantMessage message) {
        if (message.getRequestId() == null || message.getUserId() == null
                || message.getVoucherId() == null || message.getExpireAt() == null) {
            throw new IllegalArgumentException("发券消息字段不完整");
        }
    }

    private void ensureStreamGroup() {
        if (streamGroupReady) {
            return;
        }
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(VoucherGrantPublisher.STREAM_KEY))) {
            stringRedisTemplate.opsForStream().add(
                    VoucherGrantPublisher.STREAM_KEY, Collections.singletonMap("type", "init")
            );
        }
        try {
            stringRedisTemplate.opsForStream().createGroup(
                    VoucherGrantPublisher.STREAM_KEY,
                    ReadOffset.from("0-0"),
                    VoucherGrantPublisher.STREAM_GROUP
            );
        } catch (RuntimeException e) {
            if (!containsMessage(e, "BUSYGROUP")) {
                throw e;
            }
        }
        streamGroupReady = true;
    }

    private void acknowledge(RecordId recordId) {
        stringRedisTemplate.opsForStream().acknowledge(
                VoucherGrantPublisher.STREAM_KEY,
                VoucherGrantPublisher.STREAM_GROUP,
                recordId
        );
    }

    private void clearRetry(RecordId recordId) {
        stringRedisTemplate.opsForHash().delete(
                VoucherGrantPublisher.RETRY_HASH_KEY, recordId.getValue()
        );
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
