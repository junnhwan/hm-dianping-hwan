/*
package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

*/
/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 *//*

@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;
    // 用于异步下单的线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 提升作用域以让子线程拿到代理对象
    private IVoucherOrderService proxy;

    // 用于实现redis+lua判断秒杀资格
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    */
/**
     * 当这个类初始化时就立马执行，因为当这个类初始化之后随时有可能执行秒杀下单
     *//*

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new voucherOrderHandler());
    }

    */
/**
     * 基于redis的Stream消息队列处理异步秒杀
     *//*

    private class voucherOrderHandler implements Runnable {
        // 消息队列名称
        private static final String MQ_NAME = "stream.orders";

        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(MQ_NAME, ReadOffset.lastConsumed())
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有异常消息，继续下一次循环
                        continue;
                    }
                    // 3. 解析数据,转化成voucherOrder
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4. 成功，可以继续下单
                    handleVoucherOrder(voucherOrder);
                    // 5. ACK 确认
                    stringRedisTemplate.opsForStream().acknowledge(MQ_NAME, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    // 处理异常消息
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1. 获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(MQ_NAME, ReadOffset.from("0"))
                    );
                    // 2.判断pending-list信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有异常消息，跳出循环
                        break;
                    }
                    // 3. 解析数据,转化成voucherOrder
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4. 成功，可以继续下单
                    handleVoucherOrder(voucherOrder);
                    // 5. ACK 确认
                    stringRedisTemplate.opsForStream().acknowledge(MQ_NAME, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list订单异常", e);
                }
            }
        }

    */
/*//*
/ 阻塞队列，用于保存订单消息
    private final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    *//*
*/
/**
     * 线程任务类，用于处理异步下单逻辑
     *//*
*/
/*
    private class voucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 获取阻塞队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 处理阻塞队列扣减库存下单逻辑
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }*//*


        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            // 这里注意不能用ThreadLocal的userId了，因为是子线程。用order取即可
            Long userId = voucherOrder.getUserId();
            RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + userId);
            // 获取锁对象
            boolean isLock = lock.tryLock();
            if (!isLock) {
                log.error("不允许重复下单！");
                return;
            }
            // 用try-finally确保最终释放锁
            try {
                // 代理对象调用方法，确保Spring事务正常工作
                // 这里把之前这个方法重构了一下，之前是传入优惠券id执行扣减库存和创建订单的操作，
                // 现在因为改造之后订单在这之前已经创建并填入信息了（在seckillVoucher中），所以把订单传入即可
                proxy.createVoucherOrder(voucherOrder);
            } finally {
                // 释放锁
                lock.unlock();
            }
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 秒杀优化2（消息队列）：基于redis+lua实现秒杀资格判断，基于Stream消息队列实现异步秒杀下单
        // 1. 获取用户id和订单id
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("order");
        // 2. 执行lua脚本判断秒杀资格
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), orderId.toString()
        );

        int r = result.intValue();
        if (r != 0) {
            // r 不为0说明没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        // 3. 消息保存到阻塞队列

        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4. 返回订单id
        return Result.ok(orderId);
    }

    */
/*@Override
    public Result seckillVoucher(Long voucherId) {
        // 秒杀优化：基于redis+lua实现秒杀资格判断，基于阻塞队列实现异步秒杀下单
        // 1. 获取用户id和订单id
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("order");
        // 2. 执行lua脚本判断秒杀资格
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), orderId.toString()
        );

        int r = result.intValue();
        if (r != 0) {
            // r 不为0说明没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        // 3. 消息保存到阻塞队列
        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 优惠券id
        voucherOrder.setVoucherId(voucherId);
        // 用户id
        voucherOrder.setUserId(userId);
        // 订单id
        voucherOrder.setId(orderId);
        // 订单放入消息队列中
        orderTasks.add(voucherOrder);
        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4. 返回订单id
        return Result.ok(orderId);
    }*//*


    */
/*@Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 查询优惠券（在SeckillVoucherService中）
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2. 判断优惠券是否合法( i）是否在秒杀时间范围内、ii）是否库存充足)
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("活动尚未开始！");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("活动已经结束！");
        }
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足！");
        }

        // 这里注意要使用代理对象，1）要引入 aspectjweaver 依赖maven坐标  2）启动类加上注解将代理暴露启动
        // 用悲观锁实现一人一单，注意控制锁的粒度，以及 ”Spring事务未提交但锁已释放“ 的处理（获取代理对象）
        Long userId = UserHolder.getUser().getId();

        // 用redis实现的分布式锁代替synchronized，redis分布式锁优化版本1：
        // SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order" + userId);

        // 引入 redisson 后，用redisson 的redissonClient创建分布式锁
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + userId);
        // 获取锁对象
        boolean isLock = lock.tryLock();
        if(!isLock) {
            return Result.fail("不允许重复下单！");
        }
        // 用try-finally确保最终释放锁
        try {
            // 通过代理对象 proxy 调用 createVoucherOrder，就能确保 @Transactional 注解被正确处理，事务正常开启和提交。
            // 避免Spring事务失效，创建代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            // 代理对象调用方法，确保Spring事务正常工作
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 释放锁
            lock.unlock();
        }

    }*//*


    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 3. 判断当前用户是否下过单,实现一人一单
        // Long userId = voucherOrder.getUserId();

        // 已经在lua脚本判断过一人一单逻辑这里不用重复判断，避免压力数据库
        */
/*//*
/ 查询订单
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 订单是否存在
        if (count > 0) {
            log.error("用户已经下过一单！");
            return;
        }*//*


        // 4. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)   // where id = ? and stock > 0  (乐观锁CAS解决超卖)
                .update();
        if (!success) {
            log.error("库存不足！");
            return;
        }

        // 插入订单
        save(voucherOrder);
    }
}
*/
