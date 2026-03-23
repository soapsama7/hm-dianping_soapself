/**
 *
 * 因为有业务需求背景和前提，因此这段代码仅做学习用留存，直接使用的话没有实际意义，默认都是用Mutex方案
 *
 **/
package com.hmdp.utils.ShopCacheStrategyImpl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.tool.LockGetAndRelease;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.ShopCacheStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

// 逻辑过期解决缓存穿透
@Slf4j
@Component("loginExpireStrategy")
public class PassThroughShopCacheStrategy implements ShopCacheStrategy {

    // 创建10个线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Shop queryById(Long id, Supplier<Shop> dbFallback){
        String key = CACHE_SHOP_KEY + id;
        // 从redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 这里默认一定命中，若未命中则认为不是热点key，不需要处理
        if (StrUtil.isBlank(shopJson)){
            return null;
        }

        // 若命中则需要判断缓存是否过期，先把查询到的redis字符串反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson,RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(),Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 判断逻辑是否过期，若未过期则直接返回即可，如果过期，则需要利用互斥锁，让一个线程去重建缓存，其它的线程先直接返回过期信息即可
        if (expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }

        String keyLock = LOCK_SHOP_KEY + id;
        String valueLock = UUID.randomUUID().toString();
        boolean isLock = LockGetAndRelease.tryLock(keyLock,valueLock,stringRedisTemplate);

        // 拿到锁了之后新创建一个新的线程，让它去重建缓存，自己先直接返回旧数据
        if (isLock){
            // 拿到锁之后仍然要做一次double check
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                redisData = JSONUtil.toBean(shopJson,RedisData.class);
                if (redisData.getExpireTime().isAfter(LocalDateTime.now())){
                    shop = JSONUtil.toBean((JSONObject) redisData.getData(),Shop.class);
                    LockGetAndRelease.unlock(keyLock,valueLock,stringRedisTemplate);
                    return shop;
                }
            }

            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id,20L, dbFallback);
                } catch (Exception e){
                    // 异步线程抛异常不会回到主线程，因此其抛异常没有什么意义，应该做一下日志
                    log.error("缓存重建失败，shopId={}", id, e);
                }finally{
                    LockGetAndRelease.unlock(keyLock,valueLock,stringRedisTemplate);
                }
            });
        }

        return shop;
    }

    public void saveShop2Redis(Long id, Long expireSeconds, Supplier<Shop> dbFallback){
        // 从数据库查询店铺数据
        Shop shop = dbFallback.get();
        // 封装逻辑过期时间，用有参构造函数简化一下代码
        RedisData redisData = new RedisData(shop, LocalDateTime.now().plusSeconds(expireSeconds));

        // 写入Redis，要把redisData对象转为Json字符串
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));

    }

}
