package com.github.icchon.router;

import com.github.icchon.protocol.FixParser;
import com.github.icchon.protocol.Utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Router {
    private final String _routerId;
    private final int _brokerPort;
    private final Set<Integer> _marketPorts;
    private final Map<String, Session> _localSessions = new ConcurrentHashMap<>();
    private final ExecutorService _executor = Executors.newFixedThreadPool(10);
    private final String _idIssuerUrl;
    private final HttpClient _httpClient = HttpClient.newHttpClient();
    private final ObjectMapper _objectMapper = new ObjectMapper();

    public Router(int brokerPort, Set<Integer> marketPorts) {
        this(brokerPort, marketPorts, "http://localhost:8081");
    }

    public Router(int brokerPort, Set<Integer> marketPorts, String idIssuerUrl) {
        this._routerId = UUID.randomUUID().toString().substring(0, 8);
        this._brokerPort = brokerPort;
        this._marketPorts = marketPorts;
        this._idIssuerUrl = idIssuerUrl;
    }

    public String getRouterId() {
        return _routerId;
    }

    public Session getLocalSessionByID(String id) {
        return _localSessions.get(id);
    }

    private final Map<String, Set<String>> _sessionToAliases = new ConcurrentHashMap<>();

    public void registerAlias(String alias, Session session) {
        if (alias == null || alias.isEmpty() || session == null || session.ID == null) return;

        // Track alias for local cleanup
        _sessionToAliases.computeIfAbsent(session.ID, k -> ConcurrentHashMap.newKeySet()).add(alias);

        // Register in local table for quick access
        _localSessions.put(alias, session);
        System.out.println("[ROUTING] Registered local alias '" + alias + "' for session " + session.ID);

        // Notify id-issuer based on session type
        if (session instanceof MarketSession) {
            try {
                registerMarketWithIssuer(alias, ((SocketChannel)session.key.channel()).getRemoteAddress());
            } catch (IOException e) {
                System.err.println("Failed to get remote address: " + e.getMessage());
            }
        } else if (session instanceof BrokerSession) {
            registerSessionWithIssuer(alias);
        }
    }

    private void registerMarketWithIssuer(String marketName, java.net.SocketAddress address) {
        int port = (address instanceof InetSocketAddress) ? ((InetSocketAddress) address).getPort() : 0;
        
        Map<String, Object> data = Map.of(
            "marketName", marketName,
            "domain", "localhost",
            "port", port
        );

        try {
            String json = _objectMapper.writeValueAsString(data);
            sendPost("/markets", json);
        } catch (IOException e) {
            System.err.println("Failed to serialize market data: " + e.getMessage());
        }
    }

    private void registerSessionWithIssuer(String brokerId) {
        System.out.println("[ID-ISSUER] Broker " + brokerId + " connected to Router " + _routerId);
    }

    private void sendPost(String path, String json) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(_idIssuerUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        _httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(res -> {
                    if (res.statusCode() >= 200 && res.statusCode() < 300) {
                        System.out.println("[ID-ISSUER] Successfully registered to " + path + ": " + res.body());
                    } else {
                        System.err.println("[ID-ISSUER] Failed to register to " + path + ": Status " + res.statusCode());
                    }
                })
                .exceptionally(ex -> {
                    System.err.println("[ID-ISSUER] Error communicating with issuer: " + ex.getMessage());
                    return null;
                });
    }

    public void removeSession(String sessionId) {
        _localSessions.remove(sessionId);
        Set<String> aliases = _sessionToAliases.remove(sessionId);
        if (aliases != null) {
            for (String alias : aliases) {
                _localSessions.remove(alias);
                System.out.println("[ROUTING] Removed alias '" + alias + "' for session " + sessionId);
            }
        }
    }

    public Session findSession(String targetId) {
        // Since we are strictly local for now (as per user hint), we just check our map
        return _localSessions.get(targetId);
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
                session.setOnClose(() -> removeSession(id));

                clientKey.attach(session);
                _localSessions.put(id, session);
                
                // Communicate the ID to the client
                session.prepareWrite(id + "\n");
                System.out.println("Accepted on port " + listenPort + ". Assigned ID: " + id);
            }
        } catch (Exception e) {
            System.err.println("Accept Error: " + e.getMessage());
        }
    }
}
