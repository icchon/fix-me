package com.github.icchon.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RouterInfo(
    @JsonProperty("id") String id,
    @JsonProperty("host") String host,
    @JsonProperty("broker_port") int brokerPort,
    @JsonProperty("market_port") int marketPort
) {}
