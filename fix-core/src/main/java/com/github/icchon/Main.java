package com.github.icchon;

import java.util.Set;

public class Main {
    public static void main(String[] args) {
        int brokerPort = 15000;
        Set<Integer> marketPorts = Set.of(25000, 25001);
        
        Router server = new Router(brokerPort, marketPorts);
        server.run();
    }
}
