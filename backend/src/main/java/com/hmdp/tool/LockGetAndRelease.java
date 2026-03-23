package com.hmdp.tool;

import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class LockGetAndRelease {

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public static boolean tryLock(String key, String value, StringRedisTemplate stringRedisTemplate){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, value, 30, TimeUnit.SECONDS);
        // 这里注意，setnx的返回值，即Boolean有可能为null（如redis出现异常等），因此这里要用工具类进行转换，否则可能出现空指针异常，null值视为false
        return BooleanUtil.isTrue(flag);
    }

    public static void unlock(String key,String value, StringRedisTemplate stringRedisTemplate){
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,               // Lua 脚本删除key
                Collections.singletonList(key), // KEYS
                value                        // ARGV
        );
    }

}
