package com.github.icchon;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Router implements Session.MarketRegistry {
    public final static int BROKER_PORT = 15000;
    public final static int MARKET_A_PORT = 25000;
    public final static int MARKET_B_PORT = 25001;

    private static boolean isMarketPort(int port){
        return ((port/10000) % 10 == 2);
    }
    private static boolean isBrokerPort(int port){
        return ((port/10000) % 10 == 1);
    }

    private final Map<Integer, String> _portToMarketID;
    private final int _brokerPort;
    private final Map<String, List<MarketSession>> _marketPool = new ConcurrentHashMap<>();

    Router(int brokerPort, Map<Integer, String> marketMappings){
        _brokerPort = brokerPort;
        _portToMarketID = marketMappings;
    }

    public void registerMarketSession(String marketId, MarketSession session) {
        _marketPool.computeIfAbsent(marketId, k -> Collections.synchronizedList(new ArrayList<>())).add(session);
        System.out.println("Market session registered for: " + marketId + " (Total: " + _marketPool.get(marketId).size() + ")");
    }

    public void unregisterMarketSession(String marketId, MarketSession session) {
        List<MarketSession> sessions = _marketPool.get(marketId);
        if (sessions != null) {
            sessions.remove(session);
            System.out.println("Market session unregistered for: " + marketId + " (Remaining: " + sessions.size() + ")");
        }
    }

    public MarketSession getAvailableMarketSession(String marketId) {
        List<MarketSession> sessions = _marketPool.get(marketId);
        if (sessions == null || sessions.isEmpty()) return null;
        return sessions.stream().filter(Session::isConnected).findFirst().orElse(null);
    }

    private static void setupServerSocket(Selector selector, int port) throws IOException {
        ServerSocketChannel serverCh = ServerSocketChannel.open();
        serverCh.bind(new InetSocketAddress(port));
        serverCh.configureBlocking(false);
        serverCh.register(selector, SelectionKey.OP_ACCEPT);
    }

    public void run(){
        Set<ServerSocketChannel> marketChs = new HashSet<>();
        try(Selector selector = Selector.open();){
            setupServerSocket(selector, _brokerPort);
            System.out.println("Broker listening on port: " + _brokerPort);
            for (int port : _portToMarketID.keySet()) {
                setupServerSocket(selector, port);
                System.out.println("Market [" + _portToMarketID.get(port) + "] listening on port: " + port);
            }

            while (true) {
                if (selector.select(1000) == 0) {
                    checkHeartbeats(selector);
                    continue;
                }
                
                checkHeartbeats(selector); // イベントがあった時もチェック

                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iter = selectedKeys.iterator();
                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();
                    if (!key.isValid()) continue;

                    if (key.isAcceptable()) {
                        handleAccept(key, selector);
                    } else {
                        Session session = (Session) key.attachment();
                        if (key.isReadable()) {
                            List<FixParser.ParsedData> messages = session.doRead();
                            for (FixParser.ParsedData msg : messages) {
                                handleMessage(session, msg);
                            }
                        }
                        if (key.isValid() && key.isWritable()) session.doWrite();
                    }
                }
            }
        }catch(Exception e){
            System.out.println("Error: " + e);
        }
    }

    private void checkHeartbeats(Selector selector) {
        long now = System.currentTimeMillis();
        for (SelectionKey key : selector.keys()) {
            if (key.isValid() && key.attachment() instanceof Session session) {
                if (session.getState() != Session.SessionState.ESTABLISHED &&
                    session.getState() != Session.SessionState.AWAITING_TEST_RESPONSE) continue;

                long lastRead = session.getLastReadTime();
                long lastWrite = session.getLastWriteTime();
                int hbi = session.getHeartBtInt();

                // 1. Send Heartbeat if quiet for too long
                if (now - lastWrite > hbi * 1000L) {
                    sendHeartbeat(session);
                }

                // 2. Send TestRequest if haven't heard from client
                if (now - lastRead > (hbi + 2) * 1000L && session.getState() == Session.SessionState.ESTABLISHED) {
                    sendTestRequest(session);
                }

                // 3. Timeout disconnection
                if (now - lastRead > (hbi * 2) * 1000L) {
                    System.err.println("Session Timeout: No response from " + session.ID);
                    session.setState(Session.SessionState.DISCONNECTED);
                    session.close();
                }
            }
        }
    }

    private void sendHeartbeat(Session session) {
        String msg = FixMessageBuilder.start("ROUTER", "|")
                .setMsgType("0")
                .setField(49, "ROUTER")
                .setField(56, session.ID)
                .setField(34, "1") // TODO: Manage sequence numbers
                .build();
        session.prepareWrite(msg);
    }

    private void sendTestRequest(Session session) {
        String testReqID = "TR-" + System.currentTimeMillis();
        String msg = FixMessageBuilder.start("ROUTER", "|")
                .setMsgType("1")
                .setField(49, "ROUTER")
                .setField(56, session.ID)
                .setField(34, "1")
                .setField(112, testReqID)
                .build();
        session.setState(Session.SessionState.AWAITING_TEST_RESPONSE);
        session.prepareWrite(msg);
    }

    private void handleMessage(Session session, FixParser.ParsedData msg) throws Exception {
        String msgType = msg.header().msgType();

        if (session.getState() == Session.SessionState.AWAITING_TEST_RESPONSE) {
            session.setState(Session.SessionState.ESTABLISHED);
        }

        if ("A".equals(msgType)) {
            session.processLogon(msg, this);
            return;
        }

        if ("5".equals(msgType)) {
            session.processLogout(msg);
            return;
        }

        if ("1".equals(msgType)) {
            String testReqID = msg.body().get(112);
            String response = FixMessageBuilder.start("ROUTER", "|")
                    .setMsgType("0")
                    .setField(49, "ROUTER")
                    .setField(56, session.ID)
                    .setField(34, "1")
                    .setField(112, testReqID)
                    .build();
            session.prepareWrite(response);
            return;
        }

        if ("0".equals(msgType)) {
            return;
        }

        if (session.getState() != Session.SessionState.ESTABLISHED) {
            System.out.println("Message rejected: Session [" + session.ID + "] not ESTABLISHED. MsgType: " + msgType);
            return;
        }

        session.handleMsg(msg);
    }

    private void handleAccept(SelectionKey key, Selector selector) {
        try {
            ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
            SocketChannel clientChannel = serverChannel.accept();

            if (clientChannel != null) {
                clientChannel.configureBlocking(false);
                InetSocketAddress local = (InetSocketAddress) serverChannel.getLocalAddress();
                int listenPort = local.getPort();
                SelectionKey clientKey = clientChannel.register(selector, SelectionKey.OP_READ);
                String id = Utils.getID();
                Session session;
                if (isMarketPort(listenPort)){
                    session = new MarketSession(id, clientKey);
                    registerMarketSession(_portToMarketID.get(listenPort), (MarketSession)session);
                }else if(isBrokerPort(listenPort)){
                    session = new BrokerSession(id, clientKey);
                }else{
                    throw new Exception("Unhandled Error");
                }

                clientKey.attach(session);
                session.prepareWrite(id + "\n");
                System.out.println("Accepted on port " + listenPort + ". Assigned ID: " + id);
            }
        } catch (IOException e) {
            System.out.println("Error in handleAccept: " + e);
        } catch (Exception e) {
            System.out.println("Fatal Error: " + e.getMessage());
        }
    }
}
