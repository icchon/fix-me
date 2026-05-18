package com.github.icchon;

import com.github.icchon.protocol.Config;
import com.github.icchon.router.Router;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class Main {
    public static void main(String[] args) {
        int brokerPort = Config.getInt("BROKER_PORT", 5000);
        String marketPortsStr = Config.get("MARKET_PORTS", "5001");
        Set<Integer> marketPorts = Arrays.stream(marketPortsStr.split(","))
                .map(String::trim)
                .map(Integer::parseInt)
                .collect(Collectors.toSet());
        String idIssuerUrl = Config.get("ID_ISSUER_URL", "http://localhost:8081");

        System.out.println("Starting Router (Stateless)...");
        Router router = new Router(brokerPort, marketPorts, idIssuerUrl);
        System.out.println("Router ID: " + router.getRouterId());
        System.out.println("Broker Port: " + brokerPort);
        System.out.println("Market Ports: " + marketPorts);
        System.out.println("ID Issuer: " + idIssuerUrl);
        
        new Thread(router::run, "Router-Network-Thread").start();

        System.out.println("Commands:");
        System.out.println("  killall - Force disconnect all active clients");
        System.out.println("  exit    - Graceful shutdown");

        java.util.Scanner scanner = new java.util.Scanner(System.in);
        while (true) {
            String line = scanner.nextLine();
            if ("killall".equalsIgnoreCase(line)) {
                router.killAllConnections();
            } else if ("exit".equalsIgnoreCase(line)) {
                router.stop();
                break;
            }
        }
    }
}
