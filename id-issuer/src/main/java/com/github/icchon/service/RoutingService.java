package com.github.icchon.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class RoutingService {
    private final StringRedisTemplate redisTemplate;
    private static final String ROUTING_KEY_PREFIX = "routing:";
    private static final long ROUTING_TTL_SECONDS = 3600; // 1時間

    public RoutingService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void register(String alias, String routerId) {
        String key = ROUTING_KEY_PREFIX + alias;
        redisTemplate.opsForValue().set(key, routerId, ROUTING_TTL_SECONDS, TimeUnit.SECONDS);
    }

    public String getRouterId(String alias) {
        String key = ROUTING_KEY_PREFIX + alias;
        return redisTemplate.opsForValue().get(key);
    }

    public void unregister(String alias) {
        String key = ROUTING_KEY_PREFIX + alias;
        redisTemplate.delete(key);
    }
}
