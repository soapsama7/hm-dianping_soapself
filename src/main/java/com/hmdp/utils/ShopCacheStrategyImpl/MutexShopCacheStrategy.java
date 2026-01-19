package com.hmdp.utils.ShopCacheStrategyImpl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.tool.LockGetAndRelease;
import com.hmdp.utils.ShopCacheStrategy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

// 互斥锁解决缓存穿透
@Component("mutexStrategy")
public class MutexShopCacheStrategy implements ShopCacheStrategy {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Shop queryById(Long id, Supplier<Shop> dbFallback) throws InterruptedException {
        String key = CACHE_SHOP_KEY + id;
        // 从redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 如果缓存里找到了，直接返回即可
        // 注意这里的isNotBlank仅仅判断查询到的shopJson有没有值，null、空值等不包括在内，因此下面还要做一次校验
        if (StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 如果查询到的不是null，那么就只能是我们设置的空值，也就是对缓存穿透做的防御，直接返回
        if (shopJson != null) {
            return null;
        }

        // 到这为止就是“未命中”，需要访问数据库了，且需要解决缓存击穿问题。
        String keyLock = LOCK_SHOP_KEY + id;
        String valueLock = UUID.randomUUID().toString();
        Shop shop;
        boolean isLock = false;
        // 直接用while循环来等待锁即可，但考虑到可能会出现某些异常情况导致死锁等问题，因此要设置一个最大等待时间
        long timeout = System.currentTimeMillis() + 3000; // 最多等待3秒
        while(System.currentTimeMillis() < timeout){
            isLock = LockGetAndRelease.tryLock(keyLock,valueLock,stringRedisTemplate);
            if (isLock) {
                break;
            }
            Thread.sleep(50);
        }
        if (!isLock) {
            // 如果超时3秒还没拿到锁，就直接去缓存拿数据试一下
            shopJson = stringRedisTemplate.opsForValue().get(key);
            // 如果拿到了说明已经有别的线程重建了缓存，直接返回
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            // 如果缓存里还没有，就直接返回 null，提示用户请求已超时，需要重试
            return null;
        }
        try {
            // double check
            // 这里dc的作用是拿到锁后再确认一次缓存，防止重复查询数据库。因为可能有一个线程已经拿到锁并且把要找的数据添加到缓存了
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            shop = dbFallback.get();
            // 如果数据库里面都不存在，则需要先缓存一下空值，然后返回fail，解决缓存穿透问题
            if (shop == null){
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL + RandomUtil.randomLong(1,6), TimeUnit.MINUTES);
                return null;
            }
            // 若存在则先保存到redis，然后返回给用户
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL + RandomUtil.randomLong(1,6), TimeUnit.MINUTES);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }finally {
            LockGetAndRelease.unlock(keyLock,valueLock,stringRedisTemplate);
        }
        return shop;
    }
}
