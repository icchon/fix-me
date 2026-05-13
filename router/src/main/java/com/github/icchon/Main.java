package com.github.icchon;

import com.github.icchon.router.Router;
import java.util.Set;

public class Main {
    public static void main(String[] args) {
        int brokerPort = 15000;
        Set<Integer> marketPorts = Set.of(25000);
        String idIssuerUrl = "http://localhost:8081";

        System.out.println("Starting Router (Stateless)...");
        Router router = new Router(brokerPort, marketPorts, idIssuerUrl);
        System.out.println("Router ID: " + router.getRouterId());
        router.run();
    }
}
