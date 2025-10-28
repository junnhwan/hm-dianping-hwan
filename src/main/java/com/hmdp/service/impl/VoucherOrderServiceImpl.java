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
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

        // 这里注意要使用代理对象，1）要引入 aspectjweaver 依赖maven坐标  2）启动类加上注解将代理暴露启动
        // 用悲观锁实现一人一单，注意控制锁的粒度，以及 ”Spring事务未提交但锁已释放“ 的处理（获取代理对象）
        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {
            // 通过代理对象 proxy 调用 createVoucherOrder，就能确保 @Transactional 注解被正确处理，事务正常开启和提交。
            // 避免Spring事务失效，创建代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            // 代理对象调用方法，确保Spring事务正常工作
            return proxy.createVoucherOrder(voucherId);
        }

    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 3. 判断当前用户是否下过单,实现一人一单
        Long userId = UserHolder.getUser().getId();
        // 查询订单
        Long count = query().eq("user_id", userId).eq("vouvher_id", voucherId).count();
        // 订单是否存在
        if(count > 0) {
            return Result.fail("用户已经下过一单！");
        }
        // 4. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)   // where id = ? and stock > 0  (乐观锁CAS解决超卖)
                .update();
        if(!success) {
            return Result.fail("库存不足！");
        }
        // 5. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 优惠券id
        voucherOrder.setVoucherId(voucherId);
        // 用户id
        voucherOrder.setUserId(userId);
        // 订单id
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 插入订单
        save(voucherOrder);
        // 6. 返回订单Id
        return Result.ok(orderId);
    }
}
