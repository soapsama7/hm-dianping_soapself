package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.ShopCacheStrategy;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.*;

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

    // 查询商户以展示，根据前端传的数据进行不同种类查询
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (x == null || y == null){
            // 不需要坐标就直接按数据库查
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        // 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 根据分页参数和前端传来的相应参数查询redis并注意分页和排序
        String Key = SHOP_GEO_KEY + typeId;
        /*
            这里的范围没有offset，也就是说取出来的是0-end这些数据，需要手动截取
            results数据模型大致为：
            results = {
                content: [每一个查询结果],
                其他信息（一般用不到）
               }
         */
        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                stringRedisTemplate.opsForGeo().radius(
                        Key,
                        new Circle(new Point(x, y), new Distance(5000)),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .includeDistance()
                                .limit(end)
                );
        if (results == null){
            return Result.ok(Collections.emptyList());
        }
        /*
            每个元素的大致模型为：
               {
                店铺ID,
                坐标,
                距离
               }
         */
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        // 有可能查询出来不为空，但是前端请求的起始页from大于list，导致没有数据返回使得下面的SQL查询出现npt，需要避免
        if (list.size() < from){
            return Result.ok(Collections.emptyList());
        }

        // 截取from-end的部分封装店铺信息并返回
        List<Long> ids = new ArrayList<>(list.size());
        Map<String,Distance> distanceMap = new HashMap<>(list.size());

        list.stream().skip(from).forEach(result ->{
            // 获取每个店铺的id及坐标信息并添加到相应集合
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });

        String idsStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idsStr + ")").list();
        // 给每个shop设置相应的地理位置信息
        for (Shop shop : shops){
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }

        return Result.ok(shops);
    }

}
