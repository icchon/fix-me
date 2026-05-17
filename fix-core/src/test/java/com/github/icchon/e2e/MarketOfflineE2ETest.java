package com.github.icchon.e2e;

import com.github.icchon.client.BrokerClient;
import com.github.icchon.protocol.FixParser;
import com.github.icchon.router.Router;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class MarketOfflineE2ETest {
    private HttpServer mockIdIssuer;
    private int idIssuerPort;
    private Router router;
    private int brokerPort;

    @BeforeEach
    public void setup() throws IOException {
        idIssuerPort = findFreePort();
        setupMockIdIssuer();

        brokerPort = findFreePort();
        router = new Router(brokerPort, Collections.emptySet(), "http://localhost:" + idIssuerPort);
        new Thread(router::run).start();
        try { Thread.sleep(500); } catch (InterruptedException e) {}
    }

    private void setupMockIdIssuer() throws IOException {
        mockIdIssuer = HttpServer.create(new InetSocketAddress(idIssuerPort), 0);
        
        // 全ての問い合わせに対して「マーケットは存在しない（空リスト）」を返す
        mockIdIssuer.createContext("/", exchange -> {
            String response = "[]";
            if (exchange.getRequestURI().getPath().endsWith("/validate")) {
                response = "true";
            }
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) { os.write(response.getBytes()); }
        });

        mockIdIssuer.start();
    }

    private int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @AfterEach
    public void teardown() {
        if (router != null) router.stop();
        if (mockIdIssuer != null) mockIdIssuer.stop(0);
    }

    @Test
    public void testOrderRejectedWhenMarketIsOffline() throws Exception {
        String issuerUrl = "http://localhost:" + idIssuerPort;
        BrokerClient broker = new BrokerClient("localhost", brokerPort, "OFFLINE-TEST-BROKER", issuerUrl);
        
        AtomicReference<String> rejectReason = new AtomicReference<>();
        broker.setRawMessageListener(msg -> {
            // Rawメッセージからタグ58 (Text) を簡易的に抽出
            if (msg.contains("35=8") && msg.contains("39=8")) { // Execution Report + Rejected
                int idx = msg.indexOf("58=");
                if (idx != -1) {
                    int end = msg.indexOf("|", idx);
                    rejectReason.set(msg.substring(idx + 3, end != -1 ? end : msg.length()));
                }
            }
        });

        broker.start();
        Thread.sleep(1000);

        // 存在しないマーケット "GHOST-MARKET" に注文を送る
        System.out.println(">>> Sending order to non-existent market <<<");
        broker.placeOrder("GHOST-MARKET", "AAPL", 100, 150.0, "1");

        // Routerから即座にREJECTが返ってくるはず
        long start = System.currentTimeMillis();
        while (rejectReason.get() == null && System.currentTimeMillis() - start < 5000) {
            Thread.sleep(100);
        }

        assertNotNull(rejectReason.get(), "Router should send a REJECT report when market is offline");
        assertTrue(rejectReason.get().contains("Not Found"), "Reject reason should mention 'Not Found'");
        System.out.println(">>> SUCCESS: Order rejected with reason: " + rejectReason.get());

        broker.stop();
    }
}
