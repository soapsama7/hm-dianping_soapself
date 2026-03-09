package com.hmdp;

import cn.hutool.core.lang.func.VoidFunc0;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IShopService shopService;
    @Test
    public void testConcurrentQuery() throws InterruptedException {
        Long id = 1L;

        stringRedisTemplate.delete(CACHE_SHOP_KEY + id); // 模拟缓存未命中

        // 模拟50个并发请求
        int threadCount = 50;
        CountDownLatch latch = new CountDownLatch(threadCount);


        // 循环启动50个线程，第一个线程拿到锁去查询数据库，然后存到缓存里面，其它的线程阻塞等待第一个线程拿到数据或者超时返回null
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    Shop shop = (Shop) shopService.queryById(id).getData();
                    System.out.println(Thread.currentThread().getName() + ": " + shop);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
    }


    @Autowired
    private RedisIdWorker redisIdWorker;
    // 创建一个 最多 500 个线程的线程池
    private static final ExecutorService es = Executors.newFixedThreadPool(500);
    @Test
    public void testRedisIdWorker() throws InterruptedException {

        // 可以理解为一个300的计数器。每完成一个任务-1,变成 0 时await() 才会放行
        CountDownLatch latch = new CountDownLatch(300);

        // 每个用户请求 100 次 ID，共300个线程，所以一共发起了300x100=30000个请求
        Runnable task = () -> {
            for (int i = 0; i < 100; i++){
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }

            // 表示一个进程结束了
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }

        // 阻塞当前测试线程，直到 300 个 task 全部执行完
        latch.await();
        long end = System.currentTimeMillis();

        System.out.println("time = " + (end - begin));
    }

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Test
    public void testConcurrentCreateVoucherOrder() throws InterruptedException {
        int threadCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(threadCount);

        Long voucherId = 11L;   // 数据库里真实存在的秒杀券 ID
        Long userId = 10001L;  // 随便写一个userId模拟同一个用户

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    // 模拟登录逻辑，保证ThreadLocal里面有User信息
                    UserDTO user = new UserDTO();
                    user.setId(userId);
                    UserHolder.saveUser(user);

                    Result result = voucherOrderService.seckillVoucher(voucherId);
                    if (result.getSuccess()) {
                        System.out.println(Thread.currentThread().getName()
                                + " SUCCESS, orderId=" + result.getData());
                    } else {
                        System.out.println(Thread.currentThread().getName()
                                + " FAIL, reason=" + result.getErrorMsg());
                    }


                } finally {
                    // 必须清理ThreadLocal
                    UserHolder.removeUser();
                    latch.countDown();
                }
            });
        }

        latch.await();
    }

}
