package com.github.icchon.client;

import com.github.icchon.protocol.FixMessageBuilder;
import com.github.icchon.protocol.FixParser;
import com.github.icchon.protocol.MarketInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;

/**
 * 注文の送信と約定通知の受信を担当する。
 */
public class BrokerClient extends Client {
    private final HttpClient _httpClient = HttpClient.newHttpClient();
    private final ObjectMapper _objectMapper = new ObjectMapper();
    private String _lastTargetMarket;
    private boolean _wasLoggedOn = false;

    public BrokerClient(String host, int port) {
        super(host, port);
    }

    public BrokerClient(String host, int port, String sessionId) {
        super(host, port, sessionId);
    }

    public BrokerClient(String host, int port, String sessionId, String idIssuerUrl) {
        super(host, port, sessionId, idIssuerUrl);
    }

    @Override
    protected void onRouterIdUpdated() {
        if (_wasLoggedOn) {
            System.out.println("[BROKER] Router reconnected. Auto-logon initiated...");
            sendLogon(_lastTargetMarket != null ? _lastTargetMarket : "ROUTER");
        }
    }

    @Override
    protected void onConnected() {
        System.out.println("Broker Session Established. Waiting for Router ID...");
    }

    /**
     * id-issuerからアクティブな市場一覧を取得する。
     */
    public List<MarketInfo> discoverMarkets(String idIssuerUrl) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(idIssuerUrl + "/markets"))
                .GET()
                .build();

        try {
            HttpResponse<String> response = _httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return _objectMapper.readValue(response.body(), new TypeReference<List<MarketInfo>>() {});
            }
        } catch (Exception e) {
            System.err.println("[DISCOVERY ERROR] Failed to fetch markets: " + e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * id-issuer に対してセッションIDの発行を要求する。
     */
    public String requestSessionId(String idIssuerUrl, String targetMarket) {
        java.util.Map<String, String> body = new java.util.HashMap<>();
        if (targetMarket != null) body.put("market_id", targetMarket);

        try {
            String json = _objectMapper.writeValueAsString(body);
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(idIssuerUrl + "/sessions"))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                    .build();

            java.net.http.HttpResponse<String> response = _httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 || response.statusCode() == 201) {
                return response.body().trim();
            } else {
                System.err.println("[AUTH ERROR] id-issuer returned " + response.statusCode() + ": " + response.body());
            }
        } catch (Exception e) {
            System.err.println("[AUTH ERROR] Failed to request session ID: " + e.getMessage());
        }
        return null;
    }

    /**
     * 指定したターゲットに対してLogonメッセージを送信する。
     */
    public void sendLogon(String targetId) {
        this._lastTargetMarket = (targetId == null || targetId.isEmpty()) ? "ROUTER" : targetId;
        this._wasLoggedOn = true;
        
        String myId = getId();
        String myName = (myId != null) ? myId : com.github.icchon.protocol.Config.get("BROKER_NAME", "broker-1");
        
        String actualTarget = (targetId == null || targetId.isEmpty()) ? "ROUTER" : targetId;
        
        FixMessageBuilder logon = FixMessageBuilder.start(myName, "|")
                .setMsgType("A") // Logon
                .setField(98, "0") // EncryptMethod: None
                .setField(108, "30") // HeartBtInt: 30s
                .setField(56, actualTarget) // TargetCompID
                .setField(49, myName);
        sendFix(logon);
    }

    /**
     * 指定したターゲットに対してLogoutメッセージを送信する。
     */
    public void sendLogout(String targetId) {
        String myId = getId();
        if (myId == null) return;

        String actualTarget = (targetId == null || targetId.isEmpty()) ? "ROUTER" : targetId;
        FixMessageBuilder logout = FixMessageBuilder.start(myId, "|")
                .setMsgType("5")
                .setField(56, actualTarget)
                .setField(49, myId);
        sendFix(logout);
    }

    @Override
    protected void handleMessage(FixParser.ParsedData message) {
        String msgType = message.body().get(35);
        String senderId = message.body().get(49);

        if ("8".equals(msgType)) {
            String status = message.body().get(39);
            String clOrdId = message.body().get(11);
            String text = message.body().get(58);
            
            if ("8".equals(status)) { // Rejected
                System.out.println("[REPORT] Order " + clOrdId + " is REJECTED: " + text);
                
                // 自動復旧中のログオン失敗（マーケット不在など）の場合、リトライを試みる
                if (!isLoggedOn() && _wasLoggedOn && text != null && text.contains("Not Found")) {
                    System.out.println("[RECONNECT] Target not ready. Retrying logon in 3s...");
                    new Thread(() -> {
                        try { Thread.sleep(3000); } catch (InterruptedException e) {}
                        if (!isLoggedOn() && isConnected()) {
                            sendLogon(_lastTargetMarket);
                        }
                    }).start();
                }
            } else {
                String displayStatus = "2".equals(status) ? "FILLED" : "PARTIAL";
                System.out.println("[REPORT] Order " + clOrdId + " is " + displayStatus);
            }
        } else if ("5".equals(msgType)) {
            if ("ROUTER".equals(senderId)) {
                System.out.println("[LOGOUT] Logout confirmed by ROUTER. Stopping client.");
                setId(null);
                _wasLoggedOn = false;
                stop();
            } else {
                System.out.println("[LOGOUT] Market " + senderId + " is logging out/closing.");
            }
        } else if ("A".equals(msgType)) {
            System.out.println("[LOGON] Logon response received from " + senderId);
        } else {
            System.out.println("[UNKNOWN] Message Type: " + msgType);
        }
    }

    /**
     * 注文を送信する。
     */
    public void placeOrder(String targetMarketId, String symbol, int quantity, double price, String side) {
        String myId = getId();
        if (myId == null) {
            System.err.println("Cannot place order: Not logged on (no session ID)");
            return;
        }

        FixMessageBuilder builder = FixMessageBuilder.start(myId, "|")
                .setMsgType("D") // New Order Single
                .setField(11, "ORD-" + System.currentTimeMillis())
                .setField(54, side)            // Side (1=Buy, 2=Sell)
                .setField(55, symbol)          // Instrument
                .setField(38, String.valueOf(quantity)) // Quantity
                .setField(44, String.valueOf(price))    // Price
                .setField(56, targetMarketId)  // Market
                .setField(49, myId);
        sendFix(builder);
    }
}
