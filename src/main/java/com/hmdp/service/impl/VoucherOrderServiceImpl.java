package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    /**
     * 秒杀优惠券下单
     * @param voucherId 传入的优惠券Id
     * @return 统一响应格式，返回成功或失败原因
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        // 判断秒杀时间
        if (voucher.getBeginTime().isAfter(LocalDateTime.now()) || voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("当前不在秒杀时间");
        }

        // 判断库存数量
        if (voucher.getStock() < 1){
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        /*
          获取锁，可以传三个参数，根据这里的需求可以直接写无参
          重试多久，默认为-1表示不重试
          多久释放锁，默认30s
          时间单位
         */
        boolean isLock = lock.tryLock();
        if (!isLock){
            // 若获取锁失败，则返回错误或重试。因为在这里一般是重复下单导致的，所以这里直接返回错误
            return Result.fail("不允许重复下单");
        }
        try{
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId,userId);
        }finally {
            // 释放锁
            lock.unlock();
        }

    }

    @Transactional
    public Result createVoucherOrder(Long voucherId,Long userId) {
        // 判断该用户是否已经买过
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (count > 0) {
                return Result.fail("用户已购买过一次！");
            }

            // 先让库存数减一
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId).gt("stock", 0).update();
            if (!success) {
                // 扣减库存失败，一般认为也是库存不足
                return Result.fail("库存不足");
            }

            // 创建订单
            VoucherOrder voucherOrder = new VoucherOrder();

            // 获取订单id，用前面的全局id生成器
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);

            voucherOrder.setUserId(userId);

            // 优惠券id直接根据传入的即可
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);

            // 返回订单编号
            return Result.ok(orderId);
    }
}
