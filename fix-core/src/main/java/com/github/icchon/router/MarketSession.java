package com.github.icchon.router;

import java.nio.channels.SelectionKey;

public class MarketSession extends Session {

    MarketSession(String id, SelectionKey key, Router router) {
        super(id, key, router);
        setAuthenticated(true);
    }

    @Override
    public void handleMsg(String targetID, String senderID, String payload) throws Exception {
        System.out.println("[MARKET-SESSION] Routing message from " + senderID + " to " + targetID);

        // ルーティング先のセッションを探す
        Router.RoutingResult result = _router.findSessionV2(targetID, senderID, payload);
        if (result.localSession != null) {
            System.out.println("[ROUTING] Market " + ID + " (" + senderID + ") -> Target " + targetID);
            result.localSession.prepareWrite(payload);
        } else if (!result.forwarded) {
            System.err.println("[ROUTING ERROR] Target '" + targetID + "' Not Found. Dropping message from " + senderID);
        }
    }
}
