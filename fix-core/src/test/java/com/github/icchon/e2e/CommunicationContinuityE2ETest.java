package com.github.icchon.e2e;

import com.github.icchon.client.BrokerClient;
import com.github.icchon.client.MarketClient;
import com.github.icchon.router.Router;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class CommunicationContinuityE2ETest {
    private HttpServer mockIdIssuer;
    private int idIssuerPort;

    private final Map<String, Set<String>> marketEndpoints = new ConcurrentHashMap<>();

    @BeforeEach
    public void setup() throws IOException {
        idIssuerPort = findFreePort();
        setupMockIdIssuer();
    }

    private void setupMockIdIssuer() throws IOException {
        mockIdIssuer = HttpServer.create(new InetSocketAddress(idIssuerPort), 0);
        
        // /markets -> 登録 (POST) と 取得 (GET)
        mockIdIssuer.createContext("/markets", exchange -> {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                Scanner s = new Scanner(exchange.getRequestBody()).useDelimiter("\\A");
                String body = s.hasNext() ? s.next() : "";
                String marketName = extractJsonValue(body, "market_name");
                String routerId = extractJsonValue(body, "router_id");
                String sessionId = extractJsonValue(body, "session_id");
                
                if (marketName != null && routerId != null && sessionId != null) {
                    marketEndpoints.computeIfAbsent(marketName, k -> ConcurrentHashMap.newKeySet()).add(routerId + ":" + sessionId);
                    System.out.println("[MOCK-ID] Registered " + marketName + " at " + routerId + ":" + sessionId);
                }
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
            } else {
                exchange.sendResponseHeaders(200, 2);
                try (OutputStream os = exchange.getResponseBody()) { os.write("[]".getBytes()); }
            }
        });

        // /markets/{name}/endpoints
        mockIdIssuer.createContext("/markets/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/endpoints")) {
                String marketName = path.substring(9, path.length() - 10);
                Set<String> endpoints = marketEndpoints.getOrDefault(marketName, Set.of());
                StringBuilder sb = new StringBuilder("[");
                Iterator<String> it = endpoints.iterator();
                while (it.hasNext()) {
                    sb.append("\"").append(it.next()).append("\"");
                    if (it.hasNext()) sb.append(",");
                }
                sb.append("]");
                String response = sb.toString();
                exchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = exchange.getResponseBody()) { os.write(response.getBytes()); }
            }
        });

        // 認証とルーティング登録のダミー
        mockIdIssuer.createContext("/sessions/", exchange -> {
            String response = "true";
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) { os.write(response.getBytes()); }
        });
        mockIdIssuer.createContext("/routing/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });

        // 削除 (DELETE /markets/name/{name}?router_id=...&session_id=...)
        mockIdIssuer.createContext("/markets/name/", exchange -> {
            if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                String name = exchange.getRequestURI().getPath().substring(14);
                String query = exchange.getRequestURI().getQuery();
                String rId = getQueryParam(query, "router_id");
                String sId = getQueryParam(query, "session_id");
                if (name != null) {
                    marketEndpoints.getOrDefault(name, Collections.emptySet()).remove(rId + ":" + sId);
                    System.out.println("[MOCK-ID] Unregistered " + name + " from " + rId + ":" + sId);
                }
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
            }
        });

        mockIdIssuer.start();
    }

    private String getQueryParam(String query, String key) {
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] pair = param.split("=");
            if (pair.length == 2 && pair[0].equals(key)) return pair[1];
        }
        return null;
    }

    private String extractJsonValue(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx == -1) return null;
        int start = json.indexOf("\"", idx + key.length() + 2) + 1;
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    private int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @AfterEach
    public void teardown() {
        if (mockIdIssuer != null) mockIdIssuer.stop(0);
    }

    @Test
    public void testCommunicationPersistenceAfterRouterFailover() throws Exception {
        int brokerPortA = findFreePort();
        int marketPortA = findFreePort();
        Router routerA = new Router(brokerPortA, Set.of(marketPortA), "http://localhost:" + idIssuerPort);
        Thread threadA = new Thread(routerA::run);
        threadA.start();

        int brokerPortB = findFreePort();
        int marketPortB = findFreePort();
        Router routerB = new Router(brokerPortB, Set.of(marketPortB), "http://localhost:" + idIssuerPort);
        Thread threadB = new Thread(routerB::run);
        threadB.start();

        Thread.sleep(1000);

        String issuerUrl = "http://localhost:" + idIssuerPort;
        // 1. Market-1 connects to Router A
        MarketClient market1 = new MarketClient("localhost", marketPortA, "PERSIST-MARKET", issuerUrl);
        AtomicInteger ordersReceived = new AtomicInteger(0);
        market1.setRawMessageListener(msg -> {
            if (msg.contains("35=D")) ordersReceived.incrementAndGet();
        });
        market1.start();

        // 2. Broker-1 connects to Router B
        BrokerClient broker1 = new BrokerClient("localhost", brokerPortB, "PERSIST-BROKER", issuerUrl);
        broker1.start();

        Thread.sleep(2000);

        // 3. First order: B(Router B) -> M(Router A) via Redis
        broker1.placeOrder("PERSIST-MARKET", "AAPL", 10, 150.0, "1");
        
        long start = System.currentTimeMillis();
        while (ordersReceived.get() < 1 && System.currentTimeMillis() - start < 5000) {
            Thread.sleep(100);
        }
        assertEquals(1, ordersReceived.get(), "Should receive first order via cross-router routing");

        // 4. CRASH ROUTER A!
        System.out.println(">>> CRASHING ROUTER A <<<");
        routerA.stop();
        threadA.join(2000);
        market1.forceDisconnect(); // 強制的に切断をシミュレート

        // 5. Market-1 moves to Router B
        System.out.println(">>> MOVING MARKET TO ROUTER B <<<");
        // クライアントの内部状態（接続先）を書き換えて再スタート
        // 実際には LB などがあるが、ここでは直接 B のポートを指定して再開
        market1.stop();
        Thread.sleep(1000);
        
        // 再接続用の新しいインスタンス（同じID）
        MarketClient market1Moved = new MarketClient("localhost", marketPortB, "PERSIST-MARKET", issuerUrl);
        market1Moved.setRawMessageListener(msg -> {
            if (msg.contains("35=D")) ordersReceived.incrementAndGet();
        });
        market1Moved.start();

        Thread.sleep(3000); // id-issuer への再登録を待つ

        // 6. Second order: B(Router B) -> M(Router B) LOCAL!
        System.out.println(">>> SENDING SECOND ORDER AFTER FAILOVER <<<");
        broker1.placeOrder("PERSIST-MARKET", "MSFT", 20, 250.0, "1");

        start = System.currentTimeMillis();
        while (ordersReceived.get() < 2 && System.currentTimeMillis() - start < 10000) {
            Thread.sleep(100);
        }

        assertTrue(ordersReceived.get() >= 2, "Market should receive second order after moving to a new router");
        System.out.println(">>> SUCCESS: Communication persisted across router failover <<<");

        market1Moved.stop();
        broker1.stop();
        routerB.stop();
    }
}
