package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import io.reactivex.Single;
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
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
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

    // 用静态代码块初始化Lua脚本执行对象
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final ExecutorService seckill_order_executor = Executors.newSingleThreadExecutor();
    private VoucherOrderHandler handler;
    private volatile boolean running = true;
    @PostConstruct
    private void init(){
        handler = new VoucherOrderHandler();
        seckill_order_executor.submit(handler);
    }
    @PreDestroy
    public void destroy() {
        running = false;
        seckill_order_executor.shutdown();
    }

    private class VoucherOrderHandler implements Runnable{
        // 为了学习方便就直接写这里了，实际项目看情况
        private final String queueName = "stream.orders";
        @Override
        public void run(){
            while(running && !Thread.currentThread().isInterrupted()){
                try {
                    /*
                        获取消息队列中的订单信息
                        如果获取失败则继续循环读取
                        如果获取成功则可以准备下单
                     */
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    if (list == null || list.isEmpty()){
                        continue;
                    }
                    // 解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    // 完成后需要ACK确认消息已经被处理
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    // 如果是应用正在关闭，就直接退出
                    if (!running || Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    log.error("处理订单异常",e);
                    // 如果处理消息的时候抛了异常，消息也就没有被成功ACK，需要从PEL里面再取出并处理
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while(running && !Thread.currentThread().isInterrupted()){
                try {
                    /*
                        获取PEL中的订单信息
                        如果获取失败则继续循环读取
                        如果获取成功则可以准备下单
                     */
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    if (list == null || list.isEmpty()){
                        // 如果获取失败，则说明PEL里面没有异常消息，直接结束即可
                        break;
                    }
                    // 解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);

                    // 完成后需要ACK确认消息已经被处理
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    if (!running || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    log.error("处理订单异常",e);
                }
            }
        }
    }
    // 异步下单的逻辑
    private void handleVoucherOrder(VoucherOrder voucherOrder){
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = false;
        try {
            isLock = lock.tryLock(1,10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (!isLock){
            log.error("不允许重复下单");
            return;
        }
        try{
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }

    }

    private IVoucherOrderService proxy;

    /**
     * 秒杀优惠券下单
     * @param voucherId 传入的优惠券Id
     * @return 统一响应格式，返回成功或失败原因
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 从当前线程中获取用户Id
        Long userId = UserHolder.getUser().getId();
        // 获取此次的订单id
        long orderId = redisIdWorker.nextId("order");

        // 执行lua脚本，这里因为没有KEYS参数，因此直接传一个空的emptyList
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );

        // result不为0则没有购买资格，应该判断原因并返回。为0则有购买资格
        int r = result.intValue();
        if (r != 0){
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 返回订单id
        return Result.ok(orderId);
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {

        Long userId = voucherOrder.getUserId();
        // 判断该用户是否已经买过
            int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
            if (count > 0) {
                log.error("用户已购买过一次！");
                return;
            }

            // 先让库存数减一
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0).update();
            if (!success) {
                // 扣减库存失败，一般认为也是库存不足
                log.error("库存不足");
                return;
            }

            save(voucherOrder);

    }
}