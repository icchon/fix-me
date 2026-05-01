package com.github.icchon.router;

import com.github.icchon.protocol.FixParser;

import java.nio.channels.SelectionKey;

public class BrokerSession extends Session {
    private final Router _router;

    BrokerSession(String id, SelectionKey key, Router router) {
        super(id, key);
        this._router = router;
    }

    @Override
    public void handleMsg(FixParser.ParsedData data) throws Exception {
        String senderID = data.header().senderID();
        _router.registerAlias(senderID, this);

        String targetID = data.targetSessionID();
        Session targetSession = _router.getSessionByID(targetID);
        
        if (targetSession != null) {
            System.out.println("[ROUTING] Broker " + ID + " (" + senderID + ") -> Target " + targetID);
            targetSession.prepareWrite(data.fixPayload() + "\n");
        } else {
            System.out.println("[ROUTING ERROR] Target " + targetID + " not found for Broker " + ID);
        }
    }
}
