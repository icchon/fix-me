package com.github.icchon.client;

import com.github.icchon.protocol.FixMessageBuilder;
import com.github.icchon.protocol.FixParser;

/**
 * 注文の受信と約定処理を担当する。
 */
public class MarketClient extends Client {
    public MarketClient(String host, int port) {
        super(host, port);
    }

    @Override
    protected void onConnected() {
        System.out.println("Market Session Established. Waiting for logon...");
    }

    @Override
    protected void handleMessage(FixParser.ParsedData message) {
        String msgType = message.body().get(35);
        String senderId = message.body().get(49);

        if ("A".equals(msgType)) {
            System.out.println("[LOGON] Received from " + senderId + ". Sending response...");
            sendLogon(senderId);
        } else if ("D".equals(msgType)) {
            String clOrdId = message.body().get(11);
            String symbol = message.body().get(55);
            System.out.println("[ORDER] Received from Broker " + senderId + " for " + symbol);
            
            // 常に約定（FILLED）させる
            sendExecutionReport(senderId, clOrdId, "2");
        }
    }

    private void sendLogon(String targetId) {
        FixMessageBuilder logon = FixMessageBuilder.start(getId(), "|")
                .setMsgType("A")
                .setField(98, "0")
                .setField(108, "30")
                .setField(56, targetId)
                .setField(49, getId());
        sendFix(logon);
    }

    private void sendExecutionReport(String targetBrokerId, String clOrdId, String status) {
        FixMessageBuilder builder = FixMessageBuilder.start(getId(), "|")
                .setMsgType("8")
                .setField(37, "EXEC-" + System.currentTimeMillis())
                .setField(11, clOrdId)
                .setField(39, status)
                .setField(56, targetBrokerId)
                .setField(49, getId());
        sendFix(builder);
    }
}
