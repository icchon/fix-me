package com.github.icchon.router;

import com.github.icchon.protocol.FixMessageBuilder;
import com.github.icchon.protocol.FixParser;

import java.nio.channels.SelectionKey;

public class BrokerSession extends Session {

    BrokerSession(String id, SelectionKey key, Router router) {
        super(id, key, router);
    }

    @Override
    public void handleMsg(FixParser.ParsedData data) throws Exception {
        String senderID = data.header().senderID();
        String msgType = data.header().msgType();
        String targetID = data.targetSessionID();

        // 1. 認証処理
        if (!isAuthenticated()) {
            System.out.println("[AUTH] Authenticating Broker session: " + senderID + " (MsgType: " + msgType + ")");
            
            // id-issuer で検証を試みる
            if (_router.validateBrokerSession(senderID)) {
                System.out.println("[AUTH] Broker session validated: " + senderID);
                setAuthenticated(true);
                _router.registerAlias(senderID, this);
            } else if ("A".equals(msgType)) {
                // Logon メッセージの場合、ID が未提示または無効なら新規発行を試みる
                System.out.println("[AUTH] No valid session ID presented. Requesting new session...");
                String realSessionId = _router.requestNewSessionId(targetID);

                if (realSessionId != null) {
                    // 新しい ID をエイリアスとして登録し、メッセージを続行
                    _router.registerAlias(realSessionId, this);
                    setAuthenticated(true);
                    System.out.println("[AUTH] Official Session ID issued: " + realSessionId);
                } else {
                    System.err.println("[AUTH FAILED] Could not issue session ID from id-issuer");
                    close();
                    return;
                }
            } else {
                // Logon 以外で未認証のメッセージは拒絶
                System.err.println("[AUTH FAILED] Unauthorized session ID: " + senderID);
                close();
                return;
            }
        }

        // 2. ROUTER 宛てのメッセージをハンドル (Logon 応答など)
        if ("ROUTER".equals(targetID)) {
            if ("A".equals(msgType)) {
                System.out.println("[ROUTING] Responding to Logon from Broker: " + senderID);
                FixMessageBuilder response = FixMessageBuilder.start(senderID, "|")
                        .setMsgType("A")
                        .setField(49, "ROUTER")
                        .setField(56, senderID);
                prepareWrite(response.build());
            }
            return;
        }

        // ルーティング先のセッションを探す
        Router.RoutingResult result = _router.findSessionV2(targetID, senderID, data.fixPayload());
        
        if (result.localSession != null) {
            System.out.println("[ROUTING] Broker " + ID + " (" + senderID + ") -> Target " + targetID);
            result.localSession.prepareWrite(senderID + "|" + data.fixPayload());
        } else if (!result.forwarded) {
            if (targetID.equals("ROUTER")) return;
            // ローカルにいない、かつ他ルーターへの転送も行われなかった場合
            sendTargetNotFound(data);
        } else {
            // 他ルーターへ転送済み。何もしない。
            System.out.println("[ROUTING] Message from " + senderID + " forwarded to remote router for target " + targetID);
        }
    }

    private void sendTargetNotFound(FixParser.ParsedData data) {
        String msgType = data.header().msgType();
        String senderID = data.header().senderID();
        String clOrdID = data.body().get(11);

        FixMessageBuilder response = FixMessageBuilder.start(ID, "|");

        if ("D".equals(msgType)) {
            // Execution Report (Rejected)
            response.setMsgType("8")
                    .setField(11, clOrdID != null ? clOrdID : "NONE")
                    .setField(17, "REJ-" + System.currentTimeMillis())
                    .setField(150, "8") // ExecType: Rejected
                    .setField(39, "8")  // OrdStatus: Rejected
                    .setField(103, "0") // OrdRejReason: Broker Option
                    .setField(58, "Target Market '" + data.targetSessionID() + "' Not Found");
        } else {
            // Session Level Reject
            response.setMsgType("3")
                    .setField(45, data.body().get(34)) // RefSeqNum
                    .setField(371, "56")               // RefTagID (TargetCompID)
                    .setField(372, msgType)            // RefMsgType
                    .setField(373, "1")                // SessionRejectReason: Required tag missing (or just invalid target)
                    .setField(58, "Target '" + data.targetSessionID() + "' Not Found");
        }
        
        response.setField(49, "ROUTER")
                .setField(56, senderID);

        prepareWrite(response.build());
    }
}
