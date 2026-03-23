package com.hmdp.utils;

/*
    全局id生成工具类
    生成的id共为64bit的序列号，第一位为符号位（不用管），中间31位为时间戳，后面32位为实际递增的序列号
 */

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 初始时间戳
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    // 时间戳左移的位数，可更改
    private static final int COUNT_BITS = 32;

    // 获取的Id为32位时间戳+订单Id（32位）共64bit
    public long nextId(String keyPrefix){
        // 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        /*
            用redis的字符串自增策略生成序列号
         */
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 把key对应的value + 1，若不存在则视为0（value必须不存在或者为整数，非整数均会报错）
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        return timestamp << COUNT_BITS | count;

    }

}
