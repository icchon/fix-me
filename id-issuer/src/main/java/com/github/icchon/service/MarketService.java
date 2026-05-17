package com.github.icchon.service;

import com.github.icchon.model.Market;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class MarketService {
    private final StringRedisTemplate redisTemplate;
    private static final String MARKET_KEY_PREFIX = "market:";
    private static final String ENDPOINTS_KEY_PREFIX = "market_endpoints:";
    private static final String MARKET_ID_TO_NAME_KEY = "market_id_to_name:";

    public MarketService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public List<Market> getAllMarkets() {
        Set<String> keys = redisTemplate.keys(MARKET_KEY_PREFIX + "*");
        if (keys == null) return List.of();
        
        List<Market> markets = new ArrayList<>();
        for (String key : keys) {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
            if (!entries.isEmpty()) {
                Market m = mapToMarket(entries);
                if (m != null) markets.add(m);
            }
        }
        return markets;
    }

    public Market getMarketById(String marketId) {
        String name = redisTemplate.opsForValue().get(MARKET_ID_TO_NAME_KEY + marketId);
        if (name != null) {
            return getMarketByName(name);
        }
        // フォールバック
        return getAllMarkets().stream()
                .filter(m -> m.marketId().equals(marketId))
                .findFirst()
                .orElse(null);
    }

    public Market getMarketByName(String name) {
        String key = MARKET_KEY_PREFIX + name;
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        if (entries.isEmpty()) return null;
        return mapToMarket(entries);
    }

    /**
     * 特定のマーケット名に紐づくすべてのエンドポイント (routerId:sessionId) を取得する
     */
    public List<String> getMarketEndpoints(String marketName) {
        Set<String> endpoints = redisTemplate.opsForSet().members(ENDPOINTS_KEY_PREFIX + marketName);
        return (endpoints != null) ? new ArrayList<>(endpoints) : List.of();
    }

    public Market createMarket(String name, String domain, int port, String routerId, String sessionId) {
        String marketId = UUID.randomUUID().toString().substring(0, 8);
        Market market = new Market(marketId, name, domain, port, routerId, sessionId, Instant.now());
        
        saveMarket(market);
        
        // エンドポイントの登録
        if (routerId != null && sessionId != null) {
            String endpoint = routerId + ":" + sessionId;
            redisTemplate.opsForSet().add(ENDPOINTS_KEY_PREFIX + name, endpoint);
            System.out.println("[ID-ISSUER] Registered endpoint " + endpoint + " for " + name);
        }
        
        return market;
    }

    public Market updateMarket(String marketId, String name, String domain, int port, String routerId, String sessionId) {
        Market existing = getMarketById(marketId);
        if (existing == null) {
            // 新規作成に近い挙動
            return createMarket(name, domain, port, routerId, sessionId);
        }

        if (!existing.marketName().equals(name)) {
            redisTemplate.delete(MARKET_KEY_PREFIX + existing.marketName());
            redisTemplate.delete(ENDPOINTS_KEY_PREFIX + existing.marketName());
        }

        Market updated = new Market(marketId, name, domain, port, routerId, sessionId, Instant.now());
        saveMarket(updated);

        if (routerId != null && sessionId != null) {
            String endpoint = routerId + ":" + sessionId;
            redisTemplate.opsForSet().add(ENDPOINTS_KEY_PREFIX + name, endpoint);
        }

        return updated;
    }

    public void deleteMarket(String marketId) {
        Market existing = getMarketById(marketId);
        if (existing != null) {
            deleteMarketByName(existing.marketName());
            redisTemplate.delete(MARKET_ID_TO_NAME_KEY + marketId);
        }
    }

    public void deleteMarketByName(String name) {
        redisTemplate.delete(MARKET_KEY_PREFIX + name);
        redisTemplate.delete(ENDPOINTS_KEY_PREFIX + name);
    }

    /**
     * 特定のルーター上の特定セッションのみをエンドポイントリストから削除する
     */
    public void unregisterEndpoint(String name, String routerId, String sessionId) {
        if (routerId != null && sessionId != null) {
            String endpoint = routerId + ":" + sessionId;
            redisTemplate.opsForSet().remove(ENDPOINTS_KEY_PREFIX + name, endpoint);
            System.out.println("[ID-ISSUER] Unregistered endpoint " + endpoint + " for " + name);
        }
    }

    private void saveMarket(Market market) {
        String key = MARKET_KEY_PREFIX + market.marketName();
        Map<String, String> data = new java.util.HashMap<>();
        data.put("market_id", market.marketId() != null ? market.marketId() : "");
        data.put("market_name", market.marketName() != null ? market.marketName() : "");
        data.put("domain", market.domain() != null ? market.domain() : "");
        data.put("port", String.valueOf(market.port()));
        if (market.routerId() != null) data.put("router_id", market.routerId());
        if (market.sessionId() != null) data.put("session_id", market.sessionId());
        data.put("updated_at", market.updatedAt() != null ? market.updatedAt().toString() : Instant.now().toString());
        
        redisTemplate.opsForHash().putAll(key, data);
        redisTemplate.opsForValue().set(MARKET_ID_TO_NAME_KEY + market.marketId(), market.marketName());
    }

    private Market mapToMarket(Map<Object, Object> entries) {
        try {
            return new Market(
                (String) entries.getOrDefault("market_id", ""),
                (String) entries.getOrDefault("market_name", ""),
                (String) entries.getOrDefault("domain", ""),
                Integer.parseInt((String) entries.getOrDefault("port", "0")),
                (String) entries.get("router_id"),
                (String) entries.get("session_id"),
                Instant.parse((String) entries.getOrDefault("updated_at", Instant.now().toString()))
            );
        } catch (Exception e) {
            System.err.println("[ID-ISSUER] Error mapping market data: " + e.getMessage());
            return null;
        }
    }
}
