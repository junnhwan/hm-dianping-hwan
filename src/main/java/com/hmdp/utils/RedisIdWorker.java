package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 分布式ID生成器，利用Redis的INCR命令的原子性，结合时间戳 + 序列号的方式，实现分布式全局唯一ID
 */
@Component
public class RedisIdWorker {

    /**
     * 指定的开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1761523200L;

    /**
     * 序列号（32位）
     */
    private static final int COUNT_BITS = 32;

    private final StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 生成分布式全局唯一Id
     * @param keyPrefix Redis 的业务 key 前缀
     * @return 返回生成Id
     */
    public Long nextId(String keyPrefix) {
        // 1. 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowEpochSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowEpochSecond - BEGIN_TIMESTAMP;
        // 2. 生成序列号count，以当天的时间戳为key，避免一直递增导致超出范围，最大是 2^{31}
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Long count = stringRedisTemplate.opsForValue().increment(RedisConstants.ID_PREFIX + keyPrefix + date + ":");
        // 3. 拼接并返回
        return timestamp << COUNT_BITS | count;
    }

    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2025, 10, 27, 0, 0, 0);
        long currEpochSecond = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println("second = " + currEpochSecond);
    }

}
