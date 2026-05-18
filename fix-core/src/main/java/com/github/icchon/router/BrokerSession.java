package com.github.icchon.router;

import java.nio.channels.SelectionKey;

public class BrokerSession extends Session {

    BrokerSession(String id, SelectionKey key, Router router) {
        super(id, key, router);
    }

    @Override
    public void handleMsg(String targetID, String senderID, String payload) throws Exception {
        // 純粋な中継処理: 宛先を探して転送するのみ
        Router.RoutingResult result = _router.findSessionV2(targetID, senderID, payload);
        
        if (result.localSession != null) {
            System.out.println("[ROUTING] Local: " + senderID + " -> " + targetID);
            result.localSession.prepareWrite(payload);
        } else if (!result.forwarded) {
            System.err.println("[ROUTING ERROR] Target '" + targetID + "' Not Found. Dropping message from " + senderID);
        } else {
            System.out.println("[ROUTING] Forwarded: " + senderID + " -> " + targetID + " (Remote)");
        }
    }
}
