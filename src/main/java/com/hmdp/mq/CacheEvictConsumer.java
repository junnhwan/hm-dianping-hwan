package com.hmdp.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class CacheEvictConsumer {

    private static final int MAX_RETRY = 3;
    private static final ScheduledExecutorService RETRY_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor();

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheEvictProducer cacheEvictProducer;

    @KafkaListener(topics = KafkaTopics.CACHE_EVICT, groupId = "cache-evict-group")
    public void handleCacheEvict(CacheEvictMessage message) {
        if (message == null || message.getKey() == null) {
            return;
        }
        try {
            stringRedisTemplate.delete(message.getKey());
        } catch (Exception e) {
            int nextRetry = message.getRetry() + 1;
            if (nextRetry <= MAX_RETRY) {
                long delayMs = 500L * nextRetry;
                RETRY_EXECUTOR.schedule(
                        () -> cacheEvictProducer.sendEvict(message.getKey(), nextRetry),
                        delayMs,
                        TimeUnit.MILLISECONDS
                );
            }
            log.error("缓存删除失败，key={}, retry={}", message.getKey(), message.getRetry(), e);
        }
    }
}
