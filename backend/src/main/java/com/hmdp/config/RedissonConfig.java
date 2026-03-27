package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class RedissonConfig {

    @Value("${spring.redis.host:127.0.0.1}")
    private String redisHost;

    @Value("${spring.redis.port:6379}")
    private int redisPort;

    @Value("${spring.redis.password:}")
    private String redisPassword;

    /**
     * Redisson工厂类，用于创建RedissonClient类
     * @return 配置好的RedissonClient类
     */
    @Bean
    public RedissonClient redissonClient(){
        // 配置
        Config config = new Config();
        String address = String.format("redis://%s:%d", redisHost, redisPort);
        if (StringUtils.hasText(redisPassword)) {
            config.useSingleServer()
                    .setAddress(address)
                    .setPassword(redisPassword);
        } else {
            config.useSingleServer().setAddress(address);
        }

        return Redisson.create(config);
    }
}
