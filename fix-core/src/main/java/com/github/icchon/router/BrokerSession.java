package com.github.icchon.router;

import java.nio.channels.SelectionKey;

public class BrokerSession extends Session {

    BrokerSession(String id, SelectionKey key, Router router) {
        super(id, key, router);
    }

    @Override
    public void handleMsg(String targetID, String senderID, String payload) throws Exception {
        // 1. 認証処理
        if (!isAuthenticated()) {
            System.out.println("[AUTH] Authenticating Broker session: " + senderID);
            
            // id-issuer で検証を試みる
            if (_router.validateBrokerSession(senderID)) {
                System.out.println("[AUTH] Broker session validated: " + senderID);
                setAuthenticated(true);
                _router.registerAlias(senderID, this);
            } else if (payload.contains("|35=A|")) {
                System.out.println("[AUTH] No valid session ID presented. Requesting new session...");
                String realSessionId = _router.requestNewSessionId(targetID);

                if (realSessionId != null) {
                    _router.registerAlias(realSessionId, this);
                    setAuthenticated(true);
                    System.out.println("[AUTH] Official Session ID issued: " + realSessionId);
                } else {
                    System.err.println("[AUTH FAILED] Could not issue session ID from id-issuer");
                    close();
                    return;
                }
            } else {
                System.err.println("[AUTH FAILED] Unauthorized session ID: " + senderID);
                close();
                return;
            }
        }

        // ルーティング先のセッションを探す
        Router.RoutingResult result = _router.findSessionV2(targetID, senderID, payload);
        
        if (result.localSession != null) {
            System.out.println("[ROUTING] Broker " + ID + " (" + senderID + ") -> Target " + targetID);
            // Client側でプレフィックスを剥がさなくても読めるように、
            // [AssignedID]|[TargetID]|[SenderID]|[FIX] の形式ではなく、元のFIX文字列だけを送る
            // MarketClientは SenderID を FIX の 49= で識別するが、直接送って問題ない
            result.localSession.prepareWrite(payload);
        } else if (!result.forwarded) {
            System.err.println("[ROUTING ERROR] Target '" + targetID + "' Not Found. Dropping message from " + senderID);
        } else {
            System.out.println("[ROUTING] Message from " + senderID + " forwarded to remote router for target " + targetID);
        }
    }
}
