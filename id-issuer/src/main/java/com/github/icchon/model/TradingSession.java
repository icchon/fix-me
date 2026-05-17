package com.github.icchon.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record TradingSession(
    @JsonProperty("broker_session_id") String brokerSessionId,
    @JsonProperty("market") Market market,
    @JsonProperty("issued_at") Instant issuedAt,
    @JsonProperty("expires_at") Instant expiresAt
) {}
