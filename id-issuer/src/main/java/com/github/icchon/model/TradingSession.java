package com.github.icchon.model;

import java.time.Instant;

public record TradingSession(
    String brokerSessionId,
    Market market,
    Instant issuedAt,
    Instant expiresAt
) {}
