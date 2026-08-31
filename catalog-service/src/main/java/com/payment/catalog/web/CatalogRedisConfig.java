package com.payment.catalog.web;

import com.payment.catalog.application.CatalogCacheProperties;
import com.payment.catalog.application.seckill.SeckillProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * catalog-service Redis 装配（014）：绑定缓存/秒杀配置属性，并注册秒杀原子预扣 Lua 脚本。
 */
@Configuration
@EnableConfigurationProperties({CatalogCacheProperties.class, SeckillProperties.class})
public class CatalogRedisConfig {

    @Bean
    public RedisScript<Long> seckillDeductScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("seckill-deduct.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
