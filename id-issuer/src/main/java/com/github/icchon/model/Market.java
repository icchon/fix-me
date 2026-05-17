package com.github.icchon.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record Market(
    @JsonProperty("market_id") String marketId,
    @JsonProperty("market_name") String marketName,
    @JsonProperty("domain") String domain,
    @JsonProperty("port") int port,
    @JsonProperty("router_id") String routerId,
    @JsonProperty("session_id") String sessionId,
    @JsonProperty("updated_at") Instant updatedAt
) {}
