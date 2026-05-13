package com.github.icchon.client;

import com.github.icchon.protocol.FixMessageBuilder;
import com.github.icchon.protocol.FixParser;

/**
 * 注文の送信と約定通知の受信を担当する。
 */
public class BrokerClient extends Client {
    public BrokerClient(String host, int port) {
        super(host, port);
    }

    @Override
    protected void onConnected() {
        System.out.println("Broker Session Established. Ready to logon.");
    }

    /**
     * 指定したターゲットに対してLogonメッセージを送信する。
     */
    public void sendLogon(String targetId) {
        FixMessageBuilder logon = FixMessageBuilder.start(getId(), "|")
                .setMsgType("A") // Logon
                .setField(98, "0") // EncryptMethod: None
                .setField(108, "30") // HeartBtInt: 30s
                .setField(56, targetId) // TargetCompID
                .setField(49, getId());
        sendFix(logon);
    }

    @Override
    protected void handleMessage(FixParser.ParsedData message) {
        String msgType = message.body().get(35);
        if ("8".equals(msgType)) {
            String status = message.body().get(39);
            String clOrdId = message.body().get(11);
            String displayStatus = "2".equals(status) ? "FILLED" : "REJECTED";
            System.out.println("[REPORT] Order " + clOrdId + " is " + displayStatus);
        } else {
            System.out.println("[UNKNOWN] Message Type: " + msgType);
        }
    }

    public void placeOrder(String targetMarketId, String symbol, int quantity, double price) {
        FixMessageBuilder builder = FixMessageBuilder.start(getId(), "|")
                .setMsgType("D")
                .setField(11, "ORD-" + System.currentTimeMillis())
                .setField(55, symbol)
                .setField(38, String.valueOf(quantity))
                .setField(44, String.valueOf(price))
                .setField(56, targetMarketId)
                .setField(49, getId());
        sendFix(builder);
    }
}
