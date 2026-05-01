package com.github.icchon;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Router {
    private final int _brokerPort;
    private final Set<Integer> _marketPorts;
    private final Map<String, Session> _routingTable = new ConcurrentHashMap<>();
    private final ExecutorService _executor = Executors.newFixedThreadPool(10);

    Router(int brokerPort, Set<Integer> marketPorts) {
        this._brokerPort = brokerPort;
        this._marketPorts = marketPorts;
    }

    public Session getSessionByID(String id) {
        return _routingTable.get(id);
    }

    public void registerAlias(String alias, Session session) {
        if (alias != null && !alias.isEmpty() && !_routingTable.containsKey(alias)) {
            _routingTable.put(alias, session);
            System.out.println("[ROUTING] Registered alias '" + alias + "' for session " + session.ID);
        }
    }

    private static void setupServerSocket(Selector selector, int port) throws IOException {
        ServerSocketChannel serverCh = ServerSocketChannel.open();
        serverCh.bind(new InetSocketAddress(port));
        serverCh.configureBlocking(false);
        serverCh.register(selector, SelectionKey.OP_ACCEPT);
    }

    public void run() {
        try (Selector selector = Selector.open()) {
            setupServerSocket(selector, _brokerPort);
            System.out.println("Broker listening on port: " + _brokerPort);
            for (int port : _marketPorts) {
                setupServerSocket(selector, port);
                System.out.println("Market listening on port: " + port);
            }

            while (true) {
                if (selector.select() == 0) continue;
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
                                _executor.submit(() -> {
                                    try {
                                        session.handleMsg(msg);
                                    } catch (Exception e) {
                                        System.err.println("Handler Error: " + e.getMessage());
                                    }
                                });
                            }
                        }
                        if (key.isValid() && key.isWritable()) session.doWrite();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Router Error: " + e.getMessage());
        }
    }

    private void handleAccept(SelectionKey key, Selector selector) {
        try {
            ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
            SocketChannel clientChannel = serverChannel.accept();

            if (clientChannel != null) {
                clientChannel.configureBlocking(false);
                int listenPort = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
                SelectionKey clientKey = clientChannel.register(selector, SelectionKey.OP_READ);
                
                String id = Utils.getID();
                Session session;
                if (listenPort == _brokerPort) {
                    session = new BrokerSession(id, clientKey, this);
                } else {
                    session = new MarketSession(id, clientKey, this);
                }

                clientKey.attach(session);
                _routingTable.put(id, session);
                
                // Communicate the ID to the client
                session.prepareWrite(id + "\n");
                System.out.println("Accepted on port " + listenPort + ". Assigned ID: " + id);
            }
        } catch (Exception e) {
            System.err.println("Accept Error: " + e.getMessage());
        }
    }
}
