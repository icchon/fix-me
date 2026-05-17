package com.github.icchon.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.icchon.model.Market;
import com.github.icchon.model.TradingSession;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class SessionService {
    private final StringRedisTemplate redisTemplate;
    private final MarketService marketService;
    private final ObjectMapper objectMapper;
    private static final String SESSION_KEY_PREFIX = "session:";
    private static final long SESSION_TTL_SECONDS = 1800;

    public SessionService(StringRedisTemplate redisTemplate, MarketService marketService, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.marketService = marketService;
        this.objectMapper = objectMapper;
    }

    public TradingSession createSession(String marketIdOrName) {
        Market market = null;
        if (marketIdOrName != null) {
            market = marketService.getMarketByName(marketIdOrName);
            if (market == null) {
                market = marketService.getMarketById(marketIdOrName);
            }
        }

        String sessionId = String.format("%06d", (int)(Math.random() * 900000) + 100000);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(SESSION_TTL_SECONDS, ChronoUnit.SECONDS);

        TradingSession session = new TradingSession(sessionId, market, now, expiresAt);
        
        try {
            String json = objectMapper.writeValueAsString(session);
            String key = SESSION_KEY_PREFIX + sessionId;
            redisTemplate.opsForValue().set(key, json, SESSION_TTL_SECONDS, TimeUnit.SECONDS);
            return session;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize session", e);
        }
    }

    public TradingSession getSession(String sessionId) {
        String key = SESSION_KEY_PREFIX + sessionId;
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, TradingSession.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize session", e);
        }
    }

    public boolean validateSession(String sessionId) {
        TradingSession session = getSession(sessionId);
        if (session == null) return false;
        return session.expiresAt().isAfter(Instant.now());
    }
}
