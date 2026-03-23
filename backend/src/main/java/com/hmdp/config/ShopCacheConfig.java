package com.hmdp.config;

import com.hmdp.utils.ShopCacheStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.Resource;

@Configuration
public class ShopCacheConfig {
        // 从配置文件中读取 shop.cache.strategy，如果没写，用默认值mutexStrategy互斥锁
        @Value("${shop.cache.strategy:mutexStrategy}")
        private String strategy;

        // 按字段自动注入
        @Resource(name = "mutexStrategy")
        private ShopCacheStrategy mutexStrategy;

        @Resource(name = "loginExpireStrategy")
        private ShopCacheStrategy logicalExpireStrategy;

        // 这里必须注册为Bean，否则返回值无法注入到ShopServiceImpl里面
        @Bean
        public ShopCacheStrategy shopCacheStrategy() {
            if ("loginExpireStrategy".equals(strategy)) {
                return logicalExpireStrategy;
            }
            return mutexStrategy;
        }
    }

