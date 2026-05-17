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

public class MarketFailoverE2ETest {
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
        
        // 登録とエンドポイント取得
        mockIdIssuer.createContext("/markets", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                Scanner s = new Scanner(exchange.getRequestBody()).useDelimiter("\\A");
                String body = s.hasNext() ? s.next() : "";
                String name = extractJsonValue(body, "market_name");
                String rId = extractJsonValue(body, "router_id");
                String sId = extractJsonValue(body, "session_id");
                if (name != null) marketEndpoints.computeIfAbsent(name, k -> ConcurrentHashMap.newKeySet()).add(rId + ":" + sId);
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
            } else if (path.endsWith("/endpoints")) {
                // /markets/{name}/endpoints
                String name = path.substring(9, path.length() - 10);
                Set<String> eps = marketEndpoints.getOrDefault(name, Set.of());
                String response = "[\"" + String.join("\",\"", eps) + "\"]";
                if (eps.isEmpty()) response = "[]";
                exchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = exchange.getResponseBody()) { os.write(response.getBytes()); }
            } else {
                exchange.sendResponseHeaders(200, 2);
                try (OutputStream os = exchange.getResponseBody()) { os.write("[]".getBytes()); }
            }
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

        mockIdIssuer.createContext("/sessions/", exchange -> {
            String response = "true";
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) { os.write(response.getBytes()); }
        });
        mockIdIssuer.createContext("/routing/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/endpoints")) {
                String alias = path.substring(9, path.length() - 10);
                Set<String> eps = marketEndpoints.getOrDefault(alias, Set.of());
                
                // Broker の解決（簡易的にIDが alias と同じとする）
                if (eps.isEmpty()) {
                    if ("TEST-BROKER".equals(alias)) {
                        eps = Set.of("router-a:TEST-BROKER"); // Brokerはrouter-aに繋いでいる
                    }
                }

                StringBuilder sb = new StringBuilder("[");
                Iterator<String> it = eps.iterator();
                while (it.hasNext()) {
                    sb.append("\"").append(it.next()).append("\"");
                    if (it.hasNext()) sb.append(",");
                }
                sb.append("]");
                String response = sb.toString();
                exchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = exchange.getResponseBody()) { os.write(response.getBytes()); }
            } else {
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
            }
        });

        mockIdIssuer.start();
    }

    private String extractJsonValue(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx == -1) return null;
        int start = json.indexOf("\"", idx + key.length() + 2) + 1;
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    private String getQueryParam(String query, String key) {
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] pair = param.split("=");
            if (pair.length == 2 && pair[0].equals(key)) return pair[1];
        }
        return null;
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
    public void testMarketRedundancyAndFailover() throws Exception {
        // Router A & B
        int bPortA = findFreePort(), mPortA = findFreePort();
        Router routerA = new Router(bPortA, Set.of(mPortA), "http://localhost:" + idIssuerPort);
        new Thread(routerA::run).start();

        int bPortB = findFreePort(), mPortB = findFreePort();
        Router routerB = new Router(bPortB, Set.of(mPortB), "http://localhost:" + idIssuerPort);
        new Thread(routerB::run).start();

        Thread.sleep(1000);

        String issuerUrl = "http://localhost:" + idIssuerPort;
        // 2 Markets with SAME NAME
        AtomicInteger a1Received = new AtomicInteger(0);
        MarketClient marketA1 = new MarketClient("localhost", mPortA, "MARKET-A", issuerUrl);
        marketA1.setRawMessageListener(msg -> { if (msg.contains("35=D")) a1Received.incrementAndGet(); });
        marketA1.start();

        AtomicInteger a2Received = new AtomicInteger(0);
        MarketClient marketA2 = new MarketClient("localhost", mPortB, "MARKET-A", issuerUrl);
        marketA2.setRawMessageListener(msg -> { if (msg.contains("35=D")) a2Received.incrementAndGet(); });
        marketA2.start();

        BrokerClient broker = new BrokerClient("localhost", bPortA, "TEST-BROKER", issuerUrl);
        broker.start();

        Thread.sleep(2000); // 登録待ち

        // 1. Send many orders to see Stickiness
        System.out.println(">>> Sending initial orders (Should be STICKY) <<<");
        for (int i = 0; i < 10; i++) {
            broker.placeOrder("MARKET-A", "AAPL", 1, 100.0, "1");
            Thread.sleep(100);
        }

        System.out.println("A1 received: " + a1Received.get() + ", A2 received: " + a2Received.get());
        assertTrue((a1Received.get() == 10 && a2Received.get() == 0) || (a1Received.get() == 0 && a2Received.get() == 10), 
            "All orders from the same broker should go to the same market instance (Stickiness)");

        // 2. Kill the active market
        MarketClient activeMarket = (a1Received.get() > 0) ? marketA1 : marketA2;
        MarketClient backupMarket = (a1Received.get() > 0) ? marketA2 : marketA1;
        AtomicInteger backupReceived = (a1Received.get() > 0) ? a2Received : a1Received;

        System.out.println(">>> KILLING ACTIVE MARKET <<<");
        activeMarket.stop();
        Thread.sleep(2000); 

        // 3. Send more orders - all should now go to the remaining instance
        int backupBefore = backupReceived.get();
        System.out.println(">>> Sending orders after failure (Failover) <<<");
        for (int i = 0; i < 5; i++) {
            broker.placeOrder("MARKET-A", "MSFT", 1, 200.0, "1");
            Thread.sleep(100);
        }

        assertEquals(backupBefore + 5, backupReceived.get(), "All subsequent orders should be routed to the backup instance");
        System.out.println(">>> SUCCESS: Stickiness and Failover confirmed. <<<");

        marketA2.stop();
        broker.stop();
        routerA.stop();
        routerB.stop();
    }
}
