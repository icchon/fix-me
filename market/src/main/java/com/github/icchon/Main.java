package com.github.icchon;

import com.github.icchon.client.MarketClient;
import com.github.icchon.repository.ExecutionRepository;
import java.io.IOException;

public class Main {
    public static void main(String[] args) {
        String host = com.github.icchon.protocol.Config.get("ROUTER_HOST", "localhost");
        int port = com.github.icchon.protocol.Config.getInt("ROUTER_PORT", 5001);

        try {
            String idIssuerUrl = com.github.icchon.protocol.Config.get("ID_ISSUER_URL", "http://localhost:8081");
            String marketName = com.github.icchon.protocol.Config.get("MARKET_NAME", "market-C");

            ExecutionRepository repository = new ExecutionRepository();
            MarketClient market = new MarketClient(host, port);
            
            market.setRawMessageListener(msg -> System.out.println("[RAW] " + msg));

            market.setOnExecution((message, execId) -> {
                String clOrdId = message.body().get(11);
                String symbol = message.body().get(55);
                int qty = Integer.parseInt(message.body().get(38));
                double price = Double.parseDouble(message.body().get(44));
                String side = "1"; // Assume Buy for now, or get from Tag 54 if present
                String status = "2"; // Filled

                repository.saveExecution(execId, clOrdId, symbol, side, qty, price, status);
            });

            market.start();
            System.out.println("Market is running. Press Ctrl+C to stop.");
            
            while (true) {
                Thread.sleep(1000);
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }
}
