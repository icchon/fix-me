package com.github.icchon;

import com.github.icchon.client.BrokerClient;
import java.io.IOException;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        String host = "localhost";
        int port = 15000;

        try {
            BrokerClient broker = new BrokerClient(host, port);
            broker.start();

            Scanner scanner = new Scanner(System.in);
            System.out.println("Broker started. Commands: 'buy <marketId> <symbol> <qty> <price>', 'exit'");

            while (true) {
                String line = scanner.nextLine();
                if ("exit".equalsIgnoreCase(line)) {
                    broker.stop();
                    break;
                }

                String[] parts = line.split(" ");
                if (parts.length == 5 && "buy".equalsIgnoreCase(parts[0])) {
                    String marketId = parts[1];
                    String symbol = parts[2];
                    int qty = Integer.parseInt(parts[3]);
                    double price = Double.parseDouble(parts[4]);
                    
                    broker.placeOrder(marketId, symbol, qty, price);
                } else {
                    System.out.println("Unknown command or invalid format.");
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
