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
            
            // Wait a bit for ID assignment and then send logon
            Thread.sleep(2000);
            String myId = market.getId();
            if (myId != null) {
                com.github.icchon.protocol.FixMessageBuilder logon = com.github.icchon.protocol.FixMessageBuilder.start(myId, "|")
                        .setMsgType("A")
                        .setField(98, "0")
                        .setField(108, "30")
                        .setField(49, "market-C"); // Identity as market-C
                market.sendFix(logon);
            }
            
            while (true) {
                Thread.sleep(1000);
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }
}
