package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Override
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
        // 3. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .update();
        if(!success) {
            return Result.fail("库存不足！");
        }
        // 4. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 优惠券id
        voucherOrder.setVoucherId(voucherId);
        // 用户id
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        // 订单id
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 插入订单
        save(voucherOrder);
        // 5. 返回订单Id
        return Result.ok(orderId);

    }
}
