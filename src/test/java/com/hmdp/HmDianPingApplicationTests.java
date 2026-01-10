package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;

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




}
