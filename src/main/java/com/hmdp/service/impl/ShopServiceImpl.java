package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.ShopCacheStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

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
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource(name = "shopCacheStrategy")
    private ShopCacheStrategy cacheStrategy;

    @Override
    public Result queryById(Long id) throws InterruptedException {
        Shop shop = cacheStrategy.queryById(id,() -> getById(id));
        if (shop == null){
            return Result.fail("店铺不存在或请求超时，请重试");
        }
        return Result.ok(shop);

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
