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
import java.util.stream.Collectors;

@Service
public class MarketService {
    private final StringRedisTemplate redisTemplate;
    private static final String MARKET_KEY_PREFIX = "market:";

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
                markets.add(mapToMarket(entries));
            }
        }
        return markets;
    }

    public Market getMarketById(String marketId) {
        return getAllMarkets().stream()
                .filter(m -> m.marketId().equals(marketId))
                .findFirst()
                .orElse(null);
    }

    public Market createMarket(String name, String domain, int port) {
        String marketId = UUID.randomUUID().toString().substring(0, 8);
        Market market = new Market(marketId, name, domain, port, Instant.now());
        saveMarket(market);
        return market;
    }

    public Market updateMarket(String marketId, String name, String domain, int port) {
        Market existing = getMarketById(marketId);
        if (existing == null) return null;

        if (!existing.marketName().equals(name)) {
            redisTemplate.delete(MARKET_KEY_PREFIX + existing.marketName());
        }

        Market updated = new Market(marketId, name, domain, port, Instant.now());
        saveMarket(updated);
        return updated;
    }

    public void deleteMarket(String marketId) {
        Market existing = getMarketById(marketId);
        if (existing != null) {
            redisTemplate.delete(MARKET_KEY_PREFIX + existing.marketName());
        }
    }

    private void saveMarket(Market market) {
        String key = MARKET_KEY_PREFIX + market.marketName();
        Map<String, String> data = Map.of(
            "market_id", market.marketId(),
            "market_name", market.marketName(),
            "domain", market.domain(),
            "port", String.valueOf(market.port()),
            "updated_at", market.updatedAt().toString()
        );
        redisTemplate.opsForHash().putAll(key, data);
    }

    private Market mapToMarket(Map<Object, Object> entries) {
        return new Market(
            (String) entries.get("market_id"),
            (String) entries.get("market_name"),
            (String) entries.get("domain"),
            Integer.parseInt((String) entries.get("port")),
            Instant.parse((String) entries.get("updated_at"))
        );
    }
}
