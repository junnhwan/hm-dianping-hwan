package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mq.KafkaTopics;
import com.hmdp.mq.SeckillOrderMessage;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
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
    private KafkaTemplate<String, SeckillOrderMessage> kafkaTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> SECKILL_ROLLBACK_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        SECKILL_ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        SECKILL_ROLLBACK_SCRIPT.setLocation(new ClassPathResource("seckill_rollback.lua"));
        SECKILL_ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 查询秒杀券时间合法性
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("优惠券不存在");
        }
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("活动尚未开始！");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("活动已经结束！");
        }

        // 2. 获取用户id和订单id
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("order");

        // 3. 执行lua脚本判断秒杀资格（库存 + 一人一单）
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), orderId.toString()
        );
        int r = result == null ? -1 : result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        // 4. 发送Kafka消息（异步下单）
        SeckillOrderMessage message = new SeckillOrderMessage(orderId, userId, voucherId, System.currentTimeMillis());
        try {
            kafkaTemplate.send(KafkaTopics.SECKILL_ORDER, voucherId.toString(), message)
                    .get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 发送失败，回滚Redis库存与下单标记
            rollbackSeckill(voucherId, userId);
            log.error("发送秒杀订单消息失败，voucherId={}, userId={}, orderId={}", voucherId, userId, orderId, e);
            return Result.fail("系统繁忙，请稍后重试");
        }

        // 5. 返回订单id
        return Result.ok(orderId);
    }

    @KafkaListener(topics = KafkaTopics.SECKILL_ORDER, groupId = "seckill-order-group")
    @Transactional
    public void handleSeckillOrder(SeckillOrderMessage message) {
        if (message == null) {
            return;
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(message.getOrderId());
        voucherOrder.setUserId(message.getUserId());
        voucherOrder.setVoucherId(message.getVoucherId());
        createVoucherOrder(voucherOrder);
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        if (voucherOrder == null) {
            return;
        }
        // 幂等：重复消息直接忽略
        if (getById(voucherOrder.getId()) != null) {
            return;
        }

        // 扣减数据库库存（乐观锁）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.warn("库存不足，voucherId={}, orderId={}", voucherOrder.getVoucherId(), voucherOrder.getId());
            return;
        }

        // 创建订单
        save(voucherOrder);
    }

    private void rollbackSeckill(Long voucherId, Long userId) {
        try {
            stringRedisTemplate.execute(
                    SECKILL_ROLLBACK_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(), userId.toString()
            );
        } catch (Exception e) {
            log.error("秒杀回滚失败，voucherId={}, userId={}", voucherId, userId, e);
        }
    }
}
