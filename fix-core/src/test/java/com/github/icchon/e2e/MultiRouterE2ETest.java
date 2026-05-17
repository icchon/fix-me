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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class MultiRouterE2ETest {
    private HttpServer mockIdIssuer;
    private int idIssuerPort;

    private Router routerA;
    private Router routerB;
    private int brokerPortA, marketPortA;
    private int brokerPortB, marketPortB;

    private final Map<String, List<String>> marketEndpoints = new ConcurrentHashMap<>();
    private final Map<String, String> routingTable = new ConcurrentHashMap<>();

    @BeforeEach
    public void setup() throws IOException {
        idIssuerPort = findFreePort();
        setupMockIdIssuer();

        brokerPortA = findFreePort();
        marketPortA = findFreePort();
        routerA = new Router(brokerPortA, Set.of(marketPortA), "http://localhost:" + idIssuerPort);
        new Thread(routerA::run).start();

        brokerPortB = findFreePort();
        marketPortB = findFreePort();
        routerB = new Router(brokerPortB, Set.of(marketPortB), "http://localhost:" + idIssuerPort);
        new Thread(routerB::run).start();

        try { Thread.sleep(1000); } catch (InterruptedException e) {}
    }

    private void setupMockIdIssuer() throws IOException {
        mockIdIssuer = HttpServer.create(new InetSocketAddress(idIssuerPort), 0);
        
        // /markets -> 登録 (POST) と 取得 (GET)
        mockIdIssuer.createContext("/markets", exchange -> {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                // Body例: {"market_name":"market-1","router_id":"...","session_id":"..."}
                Scanner s = new Scanner(exchange.getRequestBody()).useDelimiter("\\A");
                String body = s.hasNext() ? s.next() : "";
                
                String marketName = extractJsonValue(body, "market_name");
                String routerId = extractJsonValue(body, "router_id");
                String sessionId = extractJsonValue(body, "session_id");
                
                if (marketName != null && routerId != null && sessionId != null) {
                    marketEndpoints.computeIfAbsent(marketName, k -> new ArrayList<>()).add(routerId + ":" + sessionId);
                }
                
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
            } else if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String response = "[]"; // 簡易実装
                exchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = exchange.getResponseBody()) { os.write(response.getBytes()); }
            }
        });

        // GET /markets/{name}/endpoints
        mockIdIssuer.createContext("/markets/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/endpoints")) {
                String marketName = path.substring(9, path.length() - 10);
                List<String> endpoints = marketEndpoints.getOrDefault(marketName, List.of());
                // JSON List 形式: ["routerId:sessionId"]
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < endpoints.size(); i++) {
                    sb.append("\"").append(endpoints.get(i)).append("\"");
                    if (i < endpoints.size() - 1) sb.append(",");
                }
                sb.append("]");
                String response = sb.toString();
                exchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = exchange.getResponseBody()) { os.write(response.getBytes()); }
            }
        });

        // /sessions/.../validate -> 認証
        mockIdIssuer.createContext("/sessions/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/validate")) {
                String response = "true";
                exchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = exchange.getResponseBody()) { os.write(response.getBytes()); }
            } else {
                exchange.sendResponseHeaders(404, 0);
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

    private int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @AfterEach
    public void teardown() {
        if (routerA != null) routerA.stop();
        if (routerB != null) routerB.stop();
        if (mockIdIssuer != null) mockIdIssuer.stop(0);
    }

    @Test
    public void testCrossRouterOrderRouting() throws Exception {
        String issuerUrl = "http://localhost:" + idIssuerPort;
        // Router A に Market-1 を接続
        MarketClient market1 = new MarketClient("localhost", marketPortA, "MARKET-1", issuerUrl);
        AtomicBoolean orderReceivedAtMarket = new AtomicBoolean(false);
        market1.setRawMessageListener(msg -> {
            if (msg.contains("35=D")) orderReceivedAtMarket.set(true);
        });
        market1.start();
        
        // Router B に Broker-2 を接続
        // IDは本来 id-issuer から取るが、テスト用に固定IDをセット
        BrokerClient broker2 = new BrokerClient("localhost", brokerPortB, "BROKER-2", issuerUrl);
        broker2.start();

        // 接続と登録の完了を待つ
        Thread.sleep(2000);

        // Broker-2 (Router B) -> MARKET-1 (Router A) へ注文
        // Router B は自局に MARKET-1 がいないので、id-issuer に聞きに行き、
        // Router A の ID を得て Redis 経由で転送するはず
        broker2.placeOrder("MARKET-1", "AAPL", 100, 150.0, "1");

        // 検証: Market-1 が注文を受信すること
        long start = System.currentTimeMillis();
        while (!orderReceivedAtMarket.get() && System.currentTimeMillis() - start < 10000) {
            Thread.sleep(100);
        }

        assertTrue(orderReceivedAtMarket.get(), "Market-1 on Router A should receive order from Broker-2 on Router B");
        
        market1.stop();
        broker2.stop();
    }
}
