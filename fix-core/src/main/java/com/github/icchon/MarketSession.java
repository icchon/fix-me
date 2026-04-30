package com.github.icchon;
import java.nio.channels.SelectionKey;
import com.github.icchon.Session;

public class MarketSession extends Session {
    private SelectionKey _brokerKey;

    MarketSession(String id, SelectionKey key){
        super(id, key);
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
}
