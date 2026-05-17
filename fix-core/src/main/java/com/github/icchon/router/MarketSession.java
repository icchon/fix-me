package com.github.icchon.router;

import com.github.icchon.protocol.FixMessageBuilder;
import com.github.icchon.protocol.FixParser;

import java.nio.channels.SelectionKey;

public class MarketSession extends Session {

    MarketSession(String id, SelectionKey key, Router router) {
        super(id, key, router);
        setAuthenticated(true);
    }

    @Override
    public void handleMsg(FixParser.ParsedData data) throws Exception {
        String senderID = data.header().senderID();
        String msgType = data.header().msgType();
        String targetID = data.targetSessionID();

        System.out.println("[MARKET-SESSION] Received message type " + msgType + " from " + senderID);
        _router.registerAlias(senderID, this);

        // ROUTER 宛てのメッセージをハンドル (Logon 応答など)
        if ("ROUTER".equals(targetID)) {
            if ("A".equals(msgType)) {
                System.out.println("[ROUTING] Responding to Logon from Market: " + senderID);
                FixMessageBuilder response = FixMessageBuilder.start(senderID, "|")
                        .setMsgType("A")
                        .setField(49, "ROUTER")
                        .setField(56, senderID);
                prepareWrite(response.build());
            }
            return;
        }

        // ルーティング先のセッションを探す
        Session targetSession = _router.findSession(targetID, senderID, data.fixPayload());
        if (targetSession != null) {
            System.out.println("[ROUTING] Market " + ID + " (" + senderID + ") -> Target " + targetID);
            targetSession.prepareWrite(senderID + "|" + data.fixPayload());
        }
    }
}
