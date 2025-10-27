package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {

    // 用于缓存重建的线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将存储对象序列化为JSON, 并用redis自带过期方式写入缓存
     *
     * @param key   redis存储key
     * @param value redis存储value
     * @param time  redis过期时间设置
     * @param unit  redis过期时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 将存储对象序列化为JSON, 并用逻辑过期方式写入缓存
     *
     * @param key   redis存储key
     * @param value redis存储value
     * @param time  redis过期时间设置
     * @param unit  redis过期时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期时间，封装成RedisData对象
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入redis，不用设置redis自带过期时间
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存空值解决缓存穿透
     *
     * @param keyPrefix  redis查询key前缀
     * @param id         redis查询id
     * @param type       redis查询类型
     * @param dbFallback 查询数据库的函数
     * @param time       过期时间
     * @param unit       时间单位
     * @param <R>        redis查询返回值类型
     * @param <ID>       redis查询id的类型
     * @return 查询结果
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id,
                                               Class<R> type, Function<ID, R> dbFallback,
                                               Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 如果json不是空白, 说明有值，直接返回即可
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 说明是缓存的空白值，返回null表示告知方法要给前端返回错误信息
        if (json != null) {
            return null;
        }
        // 查数据库
        R r = dbFallback.apply(id);
        // 空说明不存在该值,缓存空串解决缓存穿透
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "",
                    RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 存在就写入redis并返回
        this.set(key, r, time, unit);
        return r;
    }

    /**
     * 逻辑过期解决缓存击穿
     *
     * @param keyPrefix  redis查询key前缀
     * @param id         redis查询id
     * @param type       redis查询类型
     * @param dbFallback 查询数据库的函数
     * @param time       过期时间
     * @param unit       时间单位
     * @param <R>        redis查询返回值类型
     * @param <ID>       redis查询id的类型
     * @return 查询结果
     */
    public <ID, R> R queryWithLogicalExpire(String keyPrefix, ID id,
                                            Class<R> type, Function<ID, R> dbFallback,
                                            Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 注意，载用逻辑过期解决缓存穿透时一般规定是有提前预热过数据的，所以如果redis为null就是数据库不存在相应数据
        if (StrUtil.isBlank(json)) {
            // 不存在数据，返回null以告知要返回错误信息
            return null;
        }
        // 将封装了逻辑过期时间的redis数据反序列化出来, 注意对应的数据要先转成JSONObject再转成Bean
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 判断是否逻辑过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期则直接返回数据
            return r;
        }
        // 过期，尝试开启新线程缓存重建
        // 先获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 如果成功获取锁，将数据封装为RedisData类，序列化为json，重建缓存
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R newR = dbFallback.apply(id);
                    // 重建缓存
                    this.setWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 没有获取成功，返回旧数据
        return r;
    }

    /**
     * 互斥锁解决缓存击穿
     *
     * @param keyPrefix  redis查询key前缀
     * @param id         redis查询id
     * @param type       redis查询类型
     * @param dbFallback 查询数据库的函数
     * @param time       过期时间
     * @param unit       时间单位
     * @param <R>        redis查询返回值类型
     * @param <ID>       redis查询id的类型
     * @return 查询结果
     */
    public <ID, R> R queryWithMutex(String keyPrefix, ID id,
                                    Class<R> type, Function<ID, R> dbFallback,
                                    Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 这里说明时解决缓存穿透时缓存的空值，返回null以告知要返回错误信息
        if(json != null) {
            return null;
        }
        // redis不存在，查数据库, 尝试获取互斥锁
        R r;
        String lockKey = LOCK_SHOP_KEY + id;
        try {
            boolean isLock = tryLock(lockKey);
            // 未获取成功，休眠并重试
            if(!isLock) {
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            // 获取成功
            r = dbFallback.apply(id);
            if(r == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放锁
            unlock(lockKey);
        }
        return r;
    }

    /**
     * 获取锁
     *
     * @param key redis中锁的key
     * @return 获取是否成功, 获取互斥锁结果布尔值
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1",
                LOCK_SHOP_TTL, TimeUnit.SECONDS);
        // 这里要拆箱用hutool判空，防止空指针异常
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     *
     * @param key redis中锁的key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

}
