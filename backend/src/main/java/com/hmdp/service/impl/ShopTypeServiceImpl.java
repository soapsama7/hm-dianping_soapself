package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LIST_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_TYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryList() {
        String key = LIST_SHOP_KEY;
        // 从缓存中查询列表，若存在则可以直接返回，否则走数据库
        String shopListJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopListJson)) {
            return Result.ok(JSONUtil.toList(shopListJson, ShopType.class));
        }

        List<ShopType> typeList = query().orderByAsc("sort").list();

        if (typeList.isEmpty()) {
            return Result.fail("数据不存在");
        }

        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(typeList),SHOP_TYPE_TTL + RandomUtil.randomLong(1,6), TimeUnit.MINUTES);

        return Result.ok(typeList);
    }
}
