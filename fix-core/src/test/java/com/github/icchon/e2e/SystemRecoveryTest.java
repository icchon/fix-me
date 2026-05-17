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
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class SystemRecoveryTest {
    private HttpServer mockIdIssuer;
    private Router router;
    private Thread routerThread;
    private int brokerPort;
    private int marketPort;
    private int idIssuerPort;

    private BrokerClient activeBroker;
    private MarketClient activeMarket;

    @BeforeEach
    public void setup() throws IOException {
        // Ensure clean state from previous runs
        if (activeBroker != null) activeBroker.stop();
        if (activeMarket != null) activeMarket.stop();
        activeBroker = null;
        activeMarket = null;

        brokerPort = findFreePort();
        marketPort = findFreePort();
        idIssuerPort = findFreePort();

        // Mock ID Issuer
        mockIdIssuer = HttpServer.create(new InetSocketAddress(idIssuerPort), 0);
        mockIdIssuer.createContext("/sessions", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String response;
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                response = "123456";
            } else if (path.endsWith("/validate")) {
                response = "true";
            } else {
                response = "false";
            }
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        mockIdIssuer.createContext("/routing/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String response;
            if (path.endsWith("/endpoints")) {
                // /routing/{alias}/endpoints
                String alias = path.substring(9, path.length() - 10);
                if ("market-A".equals(alias)) {
                    response = "[\"test-router:" + marketPort + "\"]";
                } else {
                    response = "[]";
                }
            } else if (path.endsWith("/validate")) {
                response = "true";
            } else {
                response = "false";
            }
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) { os.write(response.getBytes()); }
        });
        mockIdIssuer.createContext("/markets", exchange -> {
            String response = "[]";
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                response = "{\"market_id\":\"market-A-ID\",\"market_name\":\"market-A\",\"domain\":\"localhost\",\"port\":5001}";
                exchange.sendResponseHeaders(200, response.length());
            } else {
                exchange.sendResponseHeaders(200, response.length());
            }
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        
        mockIdIssuer.createContext("/routers", exchange -> {
            String response = "[{\"id\":\"test-router\",\"host\":\"localhost\",\"broker_port\":" + brokerPort + ",\"market_port\":" + marketPort + "}]";
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        
        mockIdIssuer.start();


        startRouter();
    }

    private int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void startRouter() {
        router = new Router(brokerPort, Set.of(marketPort), "http://localhost:" + idIssuerPort);
        routerThread = new Thread(router::run, "Test-Router-Thread");
        routerThread.start();
        try { Thread.sleep(500); } catch (InterruptedException e) {}
    }

    @AfterEach
    public void teardown() {
        if (activeBroker != null) activeBroker.stop();
        if (activeMarket != null) activeMarket.stop();
        if (router != null) router.stop();
        if (routerThread != null) {
            routerThread.interrupt();
            try { routerThread.join(2000); } catch (InterruptedException e) {}
        }
        if (mockIdIssuer != null) mockIdIssuer.stop(0);
    }

    @Test
    public void testLogonWithPreIssuedId() throws Exception {
        String issuerUrl = "http://localhost:" + idIssuerPort;
        String preIssuedId = "654321";
        AtomicBoolean brokerConnected = new AtomicBoolean(false);
        AtomicBoolean logonReceived = new AtomicBoolean(false);

        activeBroker = new BrokerClient("localhost", brokerPort, preIssuedId, issuerUrl) {
            @Override
            protected void onConnected() {
                super.onConnected();
                brokerConnected.set(true);
            }
            @Override
            protected void handleMessage(com.github.icchon.protocol.FixParser.ParsedData message) {
                if ("A".equals(message.header().msgType())) {
                    logonReceived.set(true);
                }
            }
        };
        activeBroker.setReconnectDelayMs(100);

        activeMarket = new MarketClient("localhost", marketPort, "market-A", issuerUrl) {
            @Override
            protected void onConnected() {
                super.onConnected();
            }
        };
        activeMarket.setReconnectDelayMs(100);

        activeMarket.start();
        activeBroker.start();

        long start = System.currentTimeMillis();
        while (!brokerConnected.get() && System.currentTimeMillis() - start < 10000) {
            Thread.sleep(100);
        }
        assertTrue(brokerConnected.get(), "Broker should connect");

        // Market が接続してエイリアス登録するのを待つ
        Thread.sleep(1000);

        activeBroker.sendLogon("market-A");

        start = System.currentTimeMillis();
        while (!logonReceived.get() && System.currentTimeMillis() - start < 5000) {
            Thread.sleep(100);
        }
        assertTrue(logonReceived.get(), "Broker should receive Logon response from Market");
        assertEquals(preIssuedId, activeBroker.getId(), "Session ID should remain the same");
    }

    @Test
    public void testFullFlowAfterRouterRestart() throws Exception {
        String issuerUrl = "http://localhost:" + idIssuerPort;
        AtomicBoolean brokerConnected = new AtomicBoolean(false);
        activeBroker = new BrokerClient("localhost", brokerPort, "111111", issuerUrl) {
            @Override
            protected void onConnected() {
                super.onConnected();
                brokerConnected.set(true);
            }
        };
        activeBroker.setReconnectDelayMs(100);

        AtomicBoolean marketReceivedOrder = new AtomicBoolean(false);
        activeMarket = new MarketClient("localhost", marketPort, "market-A", issuerUrl) {
            @Override
            protected void onConnected() {
                super.onConnected();
            }
            @Override
            protected void handleMessage(com.github.icchon.protocol.FixParser.ParsedData message) {
                if ("D".equals(message.header().msgType())) {
                    marketReceivedOrder.set(true);
                }
            }
        };
        activeMarket.setReconnectDelayMs(100);
        activeMarket.start();
        activeBroker.start();

        Thread.sleep(2000);
        assertTrue(brokerConnected.get(), "Broker should connect");

        router.stop();
        routerThread.join(5000);
        brokerConnected.set(false);
        Thread.sleep(1000);
        startRouter();

        long start = System.currentTimeMillis();
        while (!brokerConnected.get() && System.currentTimeMillis() - start < 15000) {
            Thread.sleep(100);
        }
        assertTrue(brokerConnected.get(), "Broker should reconnect");

        // Wait a bit for Market to also reconnect and register its alias
        Thread.sleep(1000);

        activeBroker.placeOrder("market-A", "AAPL", 100, 150.0, "1");
        
        start = System.currentTimeMillis();
        while (!marketReceivedOrder.get() && System.currentTimeMillis() - start < 10000) {
            Thread.sleep(100);
        }
        assertTrue(marketReceivedOrder.get(), "Market should receive Order after Router restart");
    }
}
