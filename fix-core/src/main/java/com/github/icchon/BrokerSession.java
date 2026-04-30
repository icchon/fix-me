package com.github.icchon;

import java.nio.channels.SelectionKey;

public class BrokerSession extends Session{
    private SelectionKey _marketKey;

    BrokerSession(String id, SelectionKey key){
        super(id, key);
    }

    @Override
    public void handleMsg(FixParser.ParsedData data) throws Exception {
        if (_marketKey != null && _marketKey.isValid()) {
            Session marketSession = (Session) _marketKey.attachment();
            marketSession.prepareWrite(data.fixPayload() + "\n");
        } else {
            System.out.println("Message dropped: No market linked for Broker [" + ID + "]");
        }
    }

    public void registerMarket(SelectionKey marketKey){
        _marketKey = marketKey;
    }
}


