package com.github.icchon;

import com.github.icchon.client.MarketClient;
import java.io.IOException;

public class Main {
    public static void main(String[] args) {
        String host = "localhost";
        int port = 25000;

        try {
            MarketClient market = new MarketClient(host, port);
            market.start();
            System.out.println("Market is running. Press Ctrl+C to stop.");
            
            // Keep the main thread alive
            while (true) {
                Thread.sleep(1000);
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }
}
