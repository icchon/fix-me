package com.github.icchon.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketInfo(
    @JsonProperty("market_id") String marketId,
    @JsonProperty("market_name") String marketName,
    @JsonProperty("domain") String domain,
    @JsonProperty("port") int port
) {}
