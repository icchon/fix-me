package com.github.icchon.model;

import java.time.Instant;

public record Market(
    String marketId,
    String marketName,
    String domain,
    int port,
    Instant updatedAt
) {}
