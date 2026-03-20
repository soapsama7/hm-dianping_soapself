package com.hmdp;

import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.geo.Point;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

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

    private final ExecutorService executor = Executors.newFixedThreadPool(200);

    @Test
    public void testSeckill_100() throws InterruptedException {
        int threadCount = 50;
        CountDownLatch latch = new CountDownLatch(threadCount);
        Long voucherId = 18L;

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            final long userId = i + 1; // 每个线程一个不同用户
            executor.submit(() -> {
                try {
                    UserDTO user = new UserDTO();
                    user.setId(userId);
                    UserHolder.saveUser(user);

                    Result result = voucherOrderService.seckillVoucher(voucherId);

                    if (result.getSuccess()) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }

                    System.out.println("用户 " + userId + " 下单结果: " + result);
                } catch (Throwable e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        Thread.sleep(5000);
        System.out.println("测试完成，成功：" + successCount.get() + "，失败：" + failCount.get());
    }

    @Test
    public void loadShopGeoData(){
        List<Shop> shopList = shopService.list();
        // 按照商户的类型Id分组，比如美食类一组、ktv类一组等
        Map<Long, List<Shop>> map = shopList.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 分组完后分批写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()){
            // 按照类型Id分组
            Long typeId = entry.getKey();
            String Key = SHOP_GEO_KEY + typeId;
            List<Shop> shopValue = entry.getValue();
            // 该list为Geo数据类型list
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shopValue.size());
            // 先写入locations，然后再统一写入Redis提升效率
            for (Shop shop : shopValue){
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(Key,locations);
        }

    }

}
