package com.github.icchon.client;

import com.github.icchon.protocol.FixMessageBuilder;
import com.github.icchon.protocol.FixParser;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 注文の受信と約定処理を担当する。
 */
public class MarketClient extends Client {
    private BiConsumer<FixParser.ParsedData, String> _onExecution;
    private String _marketName;

    // 楽器と在庫数の管理 (Requirement: A market has a list of instruments)
    private final Map<String, Integer> _instruments = new java.util.concurrent.ConcurrentHashMap<>();

    public MarketClient(String host, int port) {
        this(host, port, com.github.icchon.protocol.Config.get("MARKET_NAME", "market-A"));
    }

    public MarketClient(String host, int port, String marketName) {
        this(host, port, marketName, com.github.icchon.protocol.Config.get("ID_ISSUER_URL", "http://localhost:8081"));
    }

    public MarketClient(String host, int port, String marketName, String idIssuerUrl) {
        super(host, port, marketName, idIssuerUrl);
        this._marketName = marketName;
        // 初期在庫の設定
        _instruments.put("AAPL", 1000);
        _instruments.put("MSFT", 500);
        _instruments.put("TSLA", 200);
    }

    @Override
    protected int resolveTargetPort(java.util.Map<String, Object> routerInfo) {
        return (int) routerInfo.get("market_port");
    }

    public String getMarketName() {
        return _marketName;
    }

    public void setMarketName(String marketName) {
        this._marketName = marketName;
    }

    public void setOnExecution(BiConsumer<FixParser.ParsedData, String> handler) {
        this._onExecution = handler;
    }

    @Override
    protected void onConnected() {
        System.out.println("Market Session Established. Waiting for Router ID notification...");
    }

    @Override
    protected void onRouterIdUpdated() {
        if (_marketName != null) {
            String myRouterSessionId = getRouterInternalId();
            System.out.println("[MARKET] Identified by Router as " + myRouterSessionId + ". Sending HELLO...");
            // ID|HELLO:marketName| 形式で送信
            send(myRouterSessionId + "|HELLO:" + _marketName + "|");
        }
    }

    @Override
    protected void handleMessage(FixParser.ParsedData message) {
        String msgType = message.body().get(35);
        String senderId = message.body().get(49);

        if ("A".equals(msgType)) {
            System.out.println("[LOGON] Logon response received from " + senderId);
            // Market <=> Broker ハンドシェイク
            sendLogon(senderId);
        } else if ("D".equals(msgType)) {
            processOrder(message, senderId);
        } else if ("5".equals(msgType)) {
            if ("ROUTER".equals(senderId)) {
                System.out.println("[LOGOUT] Session termination requested by ROUTER. Resetting and disconnecting...");
                resetSession();
                forceDisconnect();
            } else {
                System.out.println("[LOGOUT] Broker " + senderId + " is logging out from this market.");
            }
        }
    }

    private void processOrder(FixParser.ParsedData message, String senderId) {
        String clOrdId = message.body().get(11);
        String symbol = message.body().get(55);
        String qtyStr = message.body().get(38);
        int qty = (qtyStr != null) ? Integer.parseInt(qtyStr) : 0;

        System.out.println("[ORDER] Received from Broker " + senderId + " for " + symbol + " (Qty: " + qty + ")");

        // Validation (Requirement: can't be executed if instrument not traded or quantity not available)
        if (!_instruments.containsKey(symbol)) {
            System.out.println("[REJECT] Instrument " + symbol + " not traded on this market.");
            sendExecutionReport(senderId, clOrdId, "REJ-" + System.currentTimeMillis(), "8"); // 8 = Rejected
            return;
        }

        int currentQty = _instruments.get(symbol);
        if (currentQty < qty) {
            System.out.println("[REJECT] Insufficient quantity for " + symbol + ". Available: " + currentQty);
            sendExecutionReport(senderId, clOrdId, "REJ-" + System.currentTimeMillis(), "8"); // 8 = Rejected
            return;
        }

        // Execute (Requirement: updates the internal instrument list)
        _instruments.put(symbol, currentQty - qty);
        String execId = "EXEC-" + System.currentTimeMillis();
        System.out.println("[EXECUTE] Order " + clOrdId + " filled. Remaining " + symbol + ": " + (currentQty - qty));

        sendExecutionReport(senderId, clOrdId, execId, "2"); // 2 = Filled

        if (_onExecution != null) {
            _onExecution.accept(message, execId);
        }
    }

    public void sendLogon(String targetId) {
        String prefixId = getId();
        if (prefixId == null) prefixId = _marketName;

        FixMessageBuilder logon = FixMessageBuilder.start(prefixId, "|")
                .setMsgType("A")
                .setField(98, "0")
                .setField(108, "30")
                .setField(56, targetId)
                .setField(49, _marketName);
        sendFix(logon);
    }

    public void sendLogout(String targetId) {
        String prefixId = getId();
        if (prefixId == null) prefixId = _marketName;

        FixMessageBuilder logout = FixMessageBuilder.start(prefixId, "|")
                .setMsgType("5")
                .setField(56, targetId)
                .setField(49, _marketName);
        sendFix(logout);
    }

    private void sendExecutionReport(String targetBrokerId, String clOrdId, String execId, String status) {
        String prefixId = getId();
        if (prefixId == null) prefixId = _marketName;

        FixMessageBuilder builder = FixMessageBuilder.start(prefixId, "|")
                .setMsgType("8")
                .setField(37, execId)
                .setField(11, clOrdId)
                .setField(39, status)
                .setField(56, targetBrokerId)
                .setField(49, _marketName);
        sendFix(builder);
    }
}
