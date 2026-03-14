package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 缓存重建线程池 */
    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
        Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        // 7.返回
        return Result.ok(shop);
    }

    public Result queryWithLogicalExpire(Long id) {
        // 1. 尝试获取redis缓存
        String cacheShopKey = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(cacheShopKey);

        // 注意,因为缓存击穿解决的是热点key问题,而逻辑过期方案一般规定在热点key是有缓存就是有数据,无缓存就是无数据,
        // 所以如果是空直接返回错误信息即可,不用像互斥锁那样处理空数据的问题

        // 2. 如果是空数据直接返回错误信息
        if(StrUtil.isBlank(shopJson)) {
            return Result.fail("店铺信息不存在!");
        }

        // 3. 不是空数据,判断是否逻辑过期
        // 3.1 先将redis取出的数据反序列化(注意Shop要先转成JSONObject再反序列化)
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 3.2 没过期直接返回数据
        if(expireTime.isAfter(LocalDateTime.now())) {
            return Result.ok(shop);
        }

        // 4. 过期了, 尝试开启新线程进行缓存重建
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 4.1 获取锁成功,开启新线程重建缓存
        if(isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShopToCache(id,CACHE_SHOP_LOGICAL_TTL);
                } catch (Exception e) {
                    log.error("异步重建店铺缓存失败，id={}", id, e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        // 获取锁失败, 返回过期数据
        return Result.ok(shop);
    }

    public void saveShopToCache(Long id, Long expireSeconds) {
        Shop shop = this.getById(id);
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        redisData.setData(shop);
        String cacheShopKey = CACHE_SHOP_KEY + id;
        stringRedisTemplate.opsForValue().set(cacheShopKey, JSONUtil.toJsonStr(redisData));
    }

    public Result queryWithMutex(Long id) {
        String cacheKey = CACHE_SHOP_KEY + id;
        // 1. 尝试缓存获取结果
        Result result = getShopFromCache(cacheKey);
        // 2. 不是null返回result, 说明要么是有值的数据--直接返回, 要么是缓存的空白值--返回错误信息
        if(result != null) {
            return result;
        }
        String lockKey = LOCK_SHOP_KEY + id;
        try {
            // 3. 开始缓存重建, 尝试获取锁
            boolean isLock = tryLock(lockKey);
            // 4. 如果没获取成功说明已经有其他线程在重建了, 休眠并递归重试
            if(!isLock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 5. 成功拿到锁, 开始重建缓存,
            // 注意需要进行双检避免多个线程堆积进行缓存重建
            result = getShopFromCache(cacheKey);
            if(result != null) {
                return result;
            }
            Shop shop = getById(id);
            // 6. 是数据库不存在的数据，缓存空值避免缓存穿透，并返回错误信息
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(cacheKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return Result.fail("店铺不存在！");
            }
            // 7. 存在数据, 写入redis并直接返回
            stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return Result.ok(shop);
        } catch (InterruptedException e) {
            throw new RuntimeException("发生异常");
        } finally {
            // 8. 返回前需要释放锁
            unlock(lockKey);
        }
    }

    private Result getShopFromCache(String key) {
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)) {
            return Result.ok(JSONUtil.toBean(shopJson, Shop.class));
        }
        // 非null说明是之前避免缓存穿透而缓存的空串, 返回错误信息
        if(shopJson != null) {
            return Result.fail("店铺不存在！");
        }
        // 缓存未命中（为null）, 需要查数据库重建缓存
        return null;
    }

    /**
     * 获取锁
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
     * @param key redis中锁的key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

//    /**
//     * 缓存空值解决缓存穿透
//     * @param id 店铺id
//     * @return 店铺查询结果
//     */
//    public Shop queryWithPassThrough(Long id) {
//        String cacheShopKey = CACHE_SHOP_KEY + id;
//        // 1. 从redis中查询数据是否存在
//        String shopJson = stringRedisTemplate.opsForValue().get(cacheShopKey);
//        // 2. 存在直接返回
//        if (StrUtil.isNotBlank(shopJson)) {
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//        // 若是空值（非null）, 返回错误信息，避免打数据库，解决缓存穿透
//        if(shopJson != null) {
//            return null;
//        }
//        // 3. 不存在则查数据库
//        Shop shop = getById(id);
//        // 4. 数据库不存在, 返回错误信息
//        if (shop == null) {
//            // 缓存空值（解决缓存穿透）
//            stringRedisTemplate.opsForValue().set(cacheShopKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return null;
//        }
//        // 5. 数据库存在, 返回信息并存入redis
//        stringRedisTemplate.opsForValue().set(cacheShopKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        return shop;
//    }

    @Override
    @Transactional   // 若缓存删除失败可以事务回滚
    public Result update(Shop shop) {
        // 1. 先更新数据库
        Long id = shop.getId();
        if(id == null) {
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        // 2. 再删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
