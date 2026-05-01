package com.github.icchon;

import com.github.icchon.router.Router;
import java.util.Set;

public class Main {
    public static void main(String[] args) {
        int brokerPort = 15000;
        Set<Integer> marketPorts = Set.of(25000);

        System.out.println("Starting Router...");
        Router router = new Router(brokerPort, marketPorts);
        router.run();
    }
}
