package com.github.icchon.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.icchon.model.RouterInfo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class RouterDiscoveryService {
    private static final String ROUTER_KEY_PREFIX = "discovery:router:";
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RouterDiscoveryService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void register(RouterInfo router) {
        try {
            String json = objectMapper.writeValueAsString(router);
            String key = ROUTER_KEY_PREFIX + router.id();
            // 60秒で期限切れ（ハートビート前提）
            redisTemplate.opsForValue().set(key, json, 60, TimeUnit.SECONDS);
            System.out.println("[DISCOVERY] Registered router: " + router.id() + " at " + router.host() + ":" + router.brokerPort());
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
    }

    public List<RouterInfo> getAllRouters() {
        return Objects.requireNonNull(redisTemplate.keys(ROUTER_KEY_PREFIX + "*")).stream()
                .map(key -> redisTemplate.opsForValue().get(key))
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, RouterInfo.class);
                    } catch (JsonProcessingException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public void unregister(String id) {
        redisTemplate.delete(ROUTER_KEY_PREFIX + id);
    }
}
