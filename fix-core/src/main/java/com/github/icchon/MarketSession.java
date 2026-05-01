package com.github.icchon;
import java.nio.channels.SelectionKey;
import com.github.icchon.Session;

public class MarketSession extends Session {
    private SelectionKey _brokerKey;

    MarketSession(String id, SelectionKey key){
        super(id, key);
    }
    @Override
    public void processLogon(FixParser.ParsedData data, MarketRegistry registry) throws Exception {
        String hbi = data.body().get(108);
        if (hbi != null) {
            this.setHeartBtInt(Integer.parseInt(hbi));
        }

        if (_brokerKey != null && _brokerKey.isValid()) {
            Session brokerSession = (Session) _brokerKey.attachment();

            brokerSession.setState(SessionState.ESTABLISHED);
            this.setState(SessionState.ESTABLISHED);

            System.out.println("Logon Response from Market [" + ID + "]. Session ESTABLISHED for Broker [" + brokerSession.ID + "]");

            this.handleMsg(data);
        } else {
            this.setState(SessionState.ESTABLISHED);
            System.out.println("Market Session [" + ID + "] established (Standalone).");
        }
    }

    @Override
    public void processLogout(FixParser.ParsedData data) throws Exception {
        if (this.getState() == SessionState.LOGOUT_SENT) {
            this.setState(SessionState.CLOSED);
            if (_brokerKey != null && _brokerKey.isValid()) {
                Session brokerSession = (Session) _brokerKey.attachment();
                if (brokerSession.getState() != SessionState.CLOSED) {
                    brokerSession.setState(SessionState.CLOSED);
                }
            }
            this.handleMsg(data);
        } else {
            this.setState(SessionState.LOGOUT_RECEIVED);
            if (_brokerKey != null && _brokerKey.isValid()) {
                Session brokerSession = (Session) _brokerKey.attachment();
                brokerSession.setState(SessionState.LOGOUT_SENT);
            }
            this.handleMsg(data);
        }
    }

    @Override
    public void handleMsg(FixParser.ParsedData data) throws Exception {
        String marketId = data.header().senderID();
        if (_brokerKey != null && _brokerKey.isValid()) {
            Session brokerSession = (Session) _brokerKey.attachment();
            brokerSession.prepareWrite(data.fixPayload() + "\n");
        }else{
            System.out.println("no market: " + marketId);
        }
    }
    public void registerBroker(SelectionKey brokerKey){
        _brokerKey = brokerKey;
    }

    public SelectionKey getBrokerKey() { return _brokerKey; }
}
