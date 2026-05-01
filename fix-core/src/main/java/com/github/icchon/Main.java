package com.github.icchon;

import java.util.Map;

public class Main {
    public static void main(String[] args) {
        int brokerPort = 15000;
        Map<Integer, String> marketMappings = Map.of(
                25000, "MARKET_A",
                25001, "MARKET_B"
        );
        Router server = new Router(brokerPort, marketMappings);
        server.run();
    }
}
