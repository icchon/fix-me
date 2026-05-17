package com.github.icchon;

import com.github.icchon.client.BrokerClient;
import java.io.IOException;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        String host = com.github.icchon.protocol.Config.get("ROUTER_HOST", "localhost");
        int port = com.github.icchon.protocol.Config.getInt("ROUTER_PORT", 5000);
        String idIssuerUrl = com.github.icchon.protocol.Config.get("ID_ISSUER_URL", "http://localhost:8081");
        String sessionId = args.length > 0 ? args[0] : null;

        try {
            BrokerClient broker = sessionId != null ? 
                    new BrokerClient(host, port, sessionId) : 
                    new BrokerClient(host, port);
            
            broker.setRawMessageListener(msg -> System.out.println("[RAW] " + msg));
            broker.start();

            Scanner scanner = new Scanner(System.in);
            if (sessionId == null) {
                System.out.println("Broker started without pre-issued session ID.");
            } else {
                System.out.println("Broker started with session ID: " + sessionId);
            }
            System.out.println("Commands:");
            System.out.println("  markets                 - List active markets");
            System.out.println("  logon <marketId>        - Logon to market");
            System.out.println("  logout <marketId>       - Logout from market");
            System.out.println("  buy <marketId> <symbol> <qty> <price>");
            System.out.println("  sell <marketId> <symbol> <qty> <price>");
            System.out.println("  exit                    - Graceful logout and exit");

            while (true) {
                String line = scanner.nextLine();
                if ("exit".equalsIgnoreCase(line)) {
                    System.out.println("Initiating graceful logout...");
                    broker.sendLogout("ROUTER"); // Router経由でログアウト
                    
                    // クライアントのネットワークスレッドが終了するのを待つ (応答受信で stop() が呼ばれるはず)
                    long startWait = System.currentTimeMillis();
                    while (broker.isRunning() && System.currentTimeMillis() - startWait < 5000) {
                        try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                    }
                    
                    if (broker.isRunning()) {
                        System.out.println("Logout timed out. Force stopping...");
                        broker.stop();
                    }
                    break;
                }

                String[] parts = line.split(" ");
                if ("markets".equalsIgnoreCase(parts[0])) {
                    var markets = broker.discoverMarkets(idIssuerUrl);
                    if (markets.isEmpty()) {
                        System.out.println("No active markets found.");
                    } else {
                        System.out.println("Active Markets:");
                        for (var m : markets) {
                            System.out.printf("  - %s (%s) @ %s:%d\n", m.marketName(), m.marketId(), m.domain(), m.port());
                        }
                    }
                } else if (parts.length == 2 && "logon".equalsIgnoreCase(parts[0])) {
                    broker.sendLogon(parts[1]);
                } else if (parts.length == 2 && "logout".equalsIgnoreCase(parts[0])) {
                    broker.sendLogout(parts[1]);
                } else if (parts.length == 5 && "buy".equalsIgnoreCase(parts[0])) {
                    String marketId = parts[1];
                    String symbol = parts[2];
                    int qty = Integer.parseInt(parts[3]);
                    double price = Double.parseDouble(parts[4]);
                    broker.placeOrder(marketId, symbol, qty, price, "1");
                } else if (parts.length == 5 && "sell".equalsIgnoreCase(parts[0])) {
                    String marketId = parts[1];
                    String symbol = parts[2];
                    int qty = Integer.parseInt(parts[3]);
                    double price = Double.parseDouble(parts[4]);
                    broker.placeOrder(marketId, symbol, qty, price, "2");
                } else {
                    System.out.println("Unknown command or invalid format.");
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
