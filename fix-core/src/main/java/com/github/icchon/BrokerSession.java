package com.github.icchon;

import java.nio.channels.SelectionKey;

public class BrokerSession extends Session{
    private SelectionKey _marketKey;

    BrokerSession(String id, SelectionKey key){
        super(id, key);
    }

    @Override
    public void processLogon(FixParser.ParsedData data, MarketRegistry registry) throws Exception {
        String targetMarketId = data.header().targetID();
        
        // HeartBtInt (Tag 108) を取得
        String hbi = data.body().get(108);
        if (hbi != null) {
            this.setHeartBtInt(Integer.parseInt(hbi));
        }

        MarketSession marketSession = registry.getAvailableMarketSession(targetMarketId);
        if (marketSession != null) {
            this.registerMarket(marketSession.key);
            marketSession.registerBroker(this.key);

            this.setState(SessionState.LOGON_RECEIVED);
            System.out.println("Logon Received from Broker [" + ID + "]. Forwarding to Market [" + targetMarketId + "]");

            this.handleMsg(data);
        } else {
            System.out.println("Logon failed: Market [" + targetMarketId + "] not available for Broker [" + ID + "]");
        }
    }

    @Override
    public void processLogout(FixParser.ParsedData data) throws Exception {
        if (this.getState() == SessionState.LOGOUT_SENT) {
            this.setState(SessionState.CLOSED);
            if (_marketKey != null && _marketKey.isValid()) {
                Session marketSession = (Session) _marketKey.attachment();
                if (marketSession.getState() != SessionState.CLOSED) {
                    marketSession.setState(SessionState.CLOSED);
                }
            }
            this.handleMsg(data);
        } else {
            this.setState(SessionState.LOGOUT_RECEIVED);
            if (_marketKey != null && _marketKey.isValid()) {
                Session marketSession = (Session) _marketKey.attachment();
                marketSession.setState(SessionState.LOGOUT_SENT);
            }
            this.handleMsg(data);
        }
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

    public SelectionKey getMarketKey() { return _marketKey; }
}


