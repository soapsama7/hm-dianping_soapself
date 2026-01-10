package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setScriptText(
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                        "   return redis.call('del', KEYS[1]) " +
                        "else " +
                        "   return 0 " +
                        "end"
        );
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) throws InterruptedException {
        /*
        缓存穿透相关代码，留作备份
        Shop shop = queryWithPassThrough(id);

         */

        // 互斥锁解决缓存击穿，基于缓存穿透代码改进
        Shop shop = queryWithMutex(id);
        if (shop == null){
            return Result.fail("店铺不存在或请求超时，请重试");
        }
        return Result.ok(shop);

    }

    public Shop queryWithMutex(Long id) throws InterruptedException {
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
        String lockValue = UUID.randomUUID().toString();
        Shop shop;
        boolean isLock = false;
        // 直接用while循环来等待锁即可，但考虑到可能会出现某些异常情况，因此要设置一个最大等待时间
        long timeout = System.currentTimeMillis() + 3000; // 最多等待3秒
        while(System.currentTimeMillis() < timeout){
            isLock = tryLock(keyLock,lockValue);
            if (isLock) {
                break;
            }
            Thread.sleep(50);
        }
        if (!isLock) {
            // 如果超时3秒还没拿到锁，就直接去缓存拿数据试一下
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            // 如果缓存里还没有，就直接返回 null 或 fail
            return null;
        }
        try {
            // double check
            // 这里dc的作用是拿到锁后再确认一次缓存，防止重复查询数据库。因为可能有一个线程已经拿到锁并且把要找的数据添加到缓存了
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            shop = getById(id);
            // 如果数据库里面都不存在，则需要先缓存一下空值，然后返回fail
            if (shop == null){
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL + RandomUtil.randomLong(1,6),TimeUnit.MINUTES);
                return null;
            }
            // 若存在则先保存到redis，然后返回给用户
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL + RandomUtil.randomLong(1,6), TimeUnit.MINUTES);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }finally {
            unlock(keyLock,lockValue);
        }
        return shop;
    }

    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 从redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 注意这里的isNotBlank仅仅判断查询到的shopJson有没有值，null、空值等不包括在内，因此下面还要做一次校验
        if (StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 如果查询到的不是null，那么就只能是我们设置的空值
        if (shopJson != null) {
            return null;
        }

        Shop shop = getById(id);
        // 如果数据库里面都不存在，则需要先缓存一下空值，然后返回fail
        if (shop == null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL + RandomUtil.randomLong(1,6),TimeUnit.MINUTES);
            return null;
        }
        // 若存在则先保存到redis，然后返回给用户
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL + RandomUtil.randomLong(1,6), TimeUnit.MINUTES);

        return shop;
    }


    private boolean tryLock(String key,String value){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, value, 10, TimeUnit.SECONDS);
        // 这里注意，setnx的返回值，即Boolean有可能为null（如redis出现异常等），因此这里要用工具类进行转换，否则可能出现空指针异常，null值视为false
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key,String value){
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,               // Lua 脚本
                Collections.singletonList(key), // KEYS
                value                        // ARGV
        );
    }


    @Override
    @Transactional
    public Result updateShop(Shop shop){
        // 若传入的店铺id为空则重试
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空，请重试");
        }

        // 先更新数据库再删缓存
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();

    }

}
