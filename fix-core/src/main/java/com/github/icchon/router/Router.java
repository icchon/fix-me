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
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

public class Router {
    private final String _routerId;
    private final int _brokerPort;
    private final Set<Integer> _marketPorts;
    private final Map<String, Session> _localSessions = new ConcurrentHashMap<>();
    private final ExecutorService _executor = Executors.newFixedThreadPool(10);
    private final String _idIssuerUrl;
    private final HttpClient _httpClient = HttpClient.newHttpClient();
    private final ObjectMapper _objectMapper = new ObjectMapper();
    private final AtomicInteger _idGenerator = new AtomicInteger(0);
    
    private final JedisPool _jedisPool;
    private final String _redisChannel;

    private volatile boolean _running = false;
    private Selector _selector;
    private final java.util.List<ServerSocketChannel> _serverChannels = new java.util.ArrayList<>();
    
    private final Queue<Runnable> _pendingTasks = new ConcurrentLinkedQueue<>();
    private final Map<String, Set<String>> _sessionToAliases = new ConcurrentHashMap<>();
    private final Map<String, String> _stickyRouting = new ConcurrentHashMap<>();

    private final java.util.Timer _discoveryTimer = new java.util.Timer("Router-Discovery-Timer", true);

    public Router(int brokerPort, Set<Integer> marketPorts) {
        this(brokerPort, marketPorts, "http://localhost:8081");
    }

    public Router(int brokerPort, Set<Integer> marketPorts, String idIssuerUrl) {
        this._routerId = UUID.randomUUID().toString().substring(0, 8);
        this._brokerPort = brokerPort;
        this._marketPorts = marketPorts;
        this._idIssuerUrl = idIssuerUrl;
        
        String redisHost = com.github.icchon.protocol.Config.get("REDIS_HOST", "localhost");
        int redisPort = com.github.icchon.protocol.Config.getInt("REDIS_PORT", 6379);
        this._jedisPool = new JedisPool(redisHost, redisPort);
        this._redisChannel = "router:" + _routerId;
    }

    public void submitTask(Runnable task) {
        _pendingTasks.add(task);
        if (_selector != null) {
            _selector.wakeup();
        }
    }

    public String getRouterId() {
        return _routerId;
    }

    public void registerAlias(String alias, Session session) {
        if (alias == null || alias.isEmpty() || session == null || session.ID == null) return;
        if (alias.equals(session.ID) || "ROUTER".equals(alias)) return;

        System.out.println("[ROUTING] Registering alias '" + alias + "' for session " + session.ID);
        _sessionToAliases.computeIfAbsent(session.ID, k -> ConcurrentHashMap.newKeySet()).add(alias);
        _localSessions.put(alias, session);
        registerRoutingWithIssuer(alias);
    }

    private void registerRoutingWithIssuer(String alias) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(_idIssuerUrl + "/routing/" + alias))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(_routerId))
                .build();
        _httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    public void registerMarketWithIssuer(String marketName, String sessionId) {
        try {
            Map<String, String> body = new java.util.HashMap<>();
            body.put("market_name", marketName);
            body.put("domain", "localhost");
            body.put("port", "0"); 
            body.put("router_id", _routerId);
            body.put("session_id", sessionId);

            String json = _objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(_idIssuerUrl + "/markets"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            _httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(res -> {
                        if (res.statusCode() >= 200 && res.statusCode() < 300) {
                            System.out.println("[ID-ISSUER] Successfully proxy-registered market: " + marketName);
                        } else {
                            System.err.println("[ID-ISSUER ERROR] Proxy registration failed: " + res.statusCode());
                        }
                    });
        } catch (Exception e) {
            System.err.println("[ID-ISSUER ERROR] " + e.getMessage());
        }
    }

    public void removeSession(String sessionId) {
        if (sessionId == null) return;
        _localSessions.remove(sessionId);
        Set<String> aliases = _sessionToAliases.remove(sessionId);
        if (aliases != null) {
            for (String alias : aliases) {
                System.out.println("[ROUTING] Cleaning up alias '" + alias + "' for session " + sessionId);
                _localSessions.remove(alias);
                unregisterRoutingWithIssuer(alias);
                unregisterMarketFromIssuer(alias, sessionId);
            }
        }
    }

    private void unregisterMarketFromIssuer(String marketName, String sessionId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(_idIssuerUrl + "/markets/name/" + marketName + "?router_id=" + _routerId + "&session_id=" + sessionId))
                .DELETE()
                .build();
        _httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(res -> {
                    if (res.statusCode() == 200) {
                        System.out.println("[ID-ISSUER] Unregistered endpoint for market '" + marketName + "'");
                    }
                });
    }

    private void unregisterRoutingWithIssuer(String alias) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(_idIssuerUrl + "/routing/" + alias))
                .DELETE()
                .build();
        _httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * ルーティング結果を表すクラス
     */
    public static class RoutingResult {
        public final Session localSession;
        public final boolean forwarded;
        public final boolean found;

        private RoutingResult(Session local, boolean forwarded, boolean found) {
            this.localSession = local;
            this.forwarded = forwarded;
            this.found = found;
        }

        public static RoutingResult local(Session s) { return new RoutingResult(s, false, true); }
        public static RoutingResult remote() { return new RoutingResult(null, true, true); }
        public static RoutingResult notFound() { return new RoutingResult(null, false, false); }
    }

    public RoutingResult findSessionV2(String targetId, String senderId, String payload) {
        if (targetId == null) return RoutingResult.notFound();
        
        String cacheKey = senderId + "->" + targetId;
        String stickyEndpoint = _stickyRouting.get(cacheKey);

        // 1. まず id-issuer に問い合わせてエンドポイントリストを取得する
        List<String> endpoints = resolveEndpointsFromIssuer(targetId);
        
        if (endpoints != null && !endpoints.isEmpty()) {
            String selected = null;

            // 2. スティッキーなターゲットがまだ有効か確認
            if (stickyEndpoint != null && endpoints.contains(stickyEndpoint)) {
                selected = stickyEndpoint;
            } else {
                // 3. 新規または再選択 (Load Balancing)
                selected = endpoints.get(new java.util.Random().nextInt(endpoints.size()));
                _stickyRouting.put(cacheKey, selected);
                System.out.println("[ROUTING] New sticky route established: " + cacheKey + " -> " + selected);
            }

            String[] parts = selected.split(":");
            if (parts.length == 2) {
                String targetRouterId = parts[0];
                String targetSessionId = parts[1];

                if (_routerId.equals(targetRouterId)) {
                    Session localSession = _localSessions.get(targetSessionId);
                    if (localSession != null) {
                        return RoutingResult.local(localSession);
                    }
                } else {
                    forwardToRemoteRouter(targetRouterId, targetSessionId, senderId, payload);
                    return RoutingResult.remote();
                }
            }
        }

        // 4. フォールバック (直接の Session ID 指定など)
        Session s = _localSessions.get(targetId);
        if (s != null) return RoutingResult.local(s);

        return RoutingResult.notFound();
    }

    @Deprecated
    public Session findSession(String targetId, String senderId, String payload) {
        RoutingResult res = findSessionV2(targetId, senderId, payload);
        return res.localSession;
    }

    private List<String> resolveEndpointsFromIssuer(String alias) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(_idIssuerUrl + "/markets/" + alias + "/endpoints"))
                    .GET()
                    .build();

            HttpResponse<String> response = _httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return _objectMapper.readValue(response.body(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            }
        } catch (Exception e) {
            System.err.println("[ROUTING ERROR] Failed to resolve endpoints: " + e.getMessage());
        }
        return List.of();
    }

    private void forwardToRemoteRouter(String targetRouterId, String targetSessionId, String senderId, String payload) {
        _executor.submit(() -> {
            try (Jedis jedis = _jedisPool.getResource()) {
                String message = targetSessionId + "|" + senderId + "|" + payload;
                jedis.publish("router:" + targetRouterId, message);
            } catch (Exception e) {
                System.err.println("[ROUTING ERROR] Failed to publish to Redis: " + e.getMessage());
            }
        });
    }

    private void startRedisSubscription() {
        new Thread(() -> {
            System.out.println("[REDIS] Subscribing to " + _redisChannel);
            try (Jedis jedis = _jedisPool.getResource()) {
                jedis.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        handleRemotePacket(message);
                    }
                }, _redisChannel);
            } catch (Exception e) {
                if (_running) {
                    System.err.println("[REDIS ERROR] Subscription failed: " + e.getMessage());
                    try { Thread.sleep(5000); } catch (InterruptedException ie) {}
                    if (_running) startRedisSubscription();
                }
            }
        }, "Redis-Sub-Thread").start();
    }

    private void handleRemotePacket(String message) {
        try {
            int firstPipe = message.indexOf('|');
            int secondPipe = message.indexOf('|', firstPipe + 1);
            if (firstPipe == -1 || secondPipe == -1) return;

            String targetSessionId = message.substring(0, firstPipe);
            String senderId = message.substring(firstPipe + 1, secondPipe);
            String payload = message.substring(secondPipe + 1);

            Session localSession = _localSessions.get(targetSessionId);
            if (localSession != null) {
                System.out.println("[ROUTING] Delivered remote packet from " + senderId + " to local session " + targetSessionId);
                localSession.prepareWrite(senderId + "|" + payload);
            }
        } catch (Exception e) {
            System.err.println("[ROUTING ERROR] Failed to handle remote packet: " + e.getMessage());
        }
    }

    public boolean validateBrokerSession(String sessionId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(_idIssuerUrl + "/sessions/" + sessionId + "/validate"))
                .GET()
                .build();
        try {
            HttpResponse<String> response = _httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return Boolean.parseBoolean(response.body());
        } catch (Exception e) {
            return false;
        }
    }

    public String requestNewSessionId(String targetMarket) {
        Map<String, String> body = new java.util.HashMap<>();
        if (targetMarket != null) body.put("market_id", targetMarket);
        try {
            String json = _objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(_idIssuerUrl + "/sessions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = _httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body().trim();
        } catch (Exception e) {
            return null;
        }
    }

    public void run() {
        _running = true;
        startRedisSubscription();
        try {
            _selector = Selector.open();
            
            ServerSocketChannel bch = setupServerSocket(_selector, _brokerPort);
            _serverChannels.add(bch);
            int actualBrokerPort = bch.socket().getLocalPort();
            System.out.println("Router Broker listening on " + actualBrokerPort);

            List<Integer> actualMarketPorts = new ArrayList<>();
            for (int port : _marketPorts) {
                ServerSocketChannel mch = setupServerSocket(_selector, port);
                _serverChannels.add(mch);
                int actualMPort = mch.socket().getLocalPort();
                actualMarketPorts.add(actualMPort);
                System.out.println("Router Market listening on " + actualMPort);
            }

            // id-issuerに自身を登録
            registerRouterWithIssuer(actualBrokerPort, actualMarketPorts.isEmpty() ? 0 : actualMarketPorts.get(0));
            // 定期的なハートビート
            _discoveryTimer.scheduleAtFixedRate(new java.util.TimerTask() {
                @Override
                public void run() {
                    registerRouterWithIssuer(actualBrokerPort, actualMarketPorts.isEmpty() ? 0 : actualMarketPorts.get(0));
                }
            }, 30000, 30000);

            while (_running && !Thread.currentThread().isInterrupted()) {
                Runnable task;
                while ((task = _pendingTasks.poll()) != null) {
                    task.run();
                }

                if (_selector.select(1000) == 0) continue;
                if (!_running) break;
                
                Set<SelectionKey> selectedKeys = _selector.selectedKeys();
                Iterator<SelectionKey> iter = selectedKeys.iterator();
                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();
                    if (!key.isValid()) continue;

                    if (key.isConnectable()) {
                        /* Client only */
                    } else if (key.isAcceptable()) {
                        handleAccept(key, _selector);
                    } else {
                        Session session = (Session) key.attachment();
                        if (session == null) continue;
                        
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
                        } else if (key.isValid() && key.isWritable()) {
                            session.doWrite();
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (_running && !(e instanceof InterruptedException)) {
                System.err.println("Router Error: " + e.getMessage());
            }
        } finally {
            cleanup();
        }
    }

    private void registerRouterWithIssuer(int brokerPort, int marketPort) {
        try {
            Map<String, Object> body = Map.of(
                "id", _routerId,
                "host", "localhost", 
                "broker_port", brokerPort,
                "market_port", marketPort
            );
            String json = _objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(_idIssuerUrl + "/routers"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            _httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            System.err.println("[DISCOVERY] Failed to register with id-issuer: " + e.getMessage());
        }
    }

    public void stop() {
        _running = false;
        if (_selector != null) _selector.wakeup();
    }

    public void killAllConnections() {
        System.out.println("[DEBUG] Killing all active client connections...");
        for (Session session : _localSessions.values()) {
            session.close();
        }
    }

    private void cleanup() {
        _discoveryTimer.cancel();
        for (Session session : _localSessions.values()) {
            session.close();
        }
        for (ServerSocketChannel ch : _serverChannels) {
            try { ch.close(); } catch (IOException e) {}
        }
        _serverChannels.clear();
        try { if (_selector != null) _selector.close(); } catch (IOException e) {}
        _executor.shutdownNow();
        if (_jedisPool != null) _jedisPool.close();
    }

    private void handleAccept(SelectionKey key, Selector selector) {
        try {
            ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
            SocketChannel clientChannel = serverChannel.accept();
            if (clientChannel == null) return;
            
            clientChannel.configureBlocking(false);
            String sessionId = String.format("%06d", _idGenerator.incrementAndGet());
            int listenPort = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
            
            Session session;
            if (listenPort == _brokerPort) {
                session = new BrokerSession(sessionId, null, this);
            } else {
                session = new MarketSession(sessionId, null, this);
            }

            SelectionKey clientKey = clientChannel.register(selector, SelectionKey.OP_READ, session);
            session.setKey(clientKey);
            session.setOnClose(() -> removeSession(sessionId));
            _localSessions.put(sessionId, session);
            
            System.out.println("Accepted Internal ID: " + sessionId + " on port " + listenPort);
            session.prepareWrite("ID:" + sessionId + ":" + _routerId + "|");
        } catch (Exception e) {
            System.err.println("Accept Error: " + e.getMessage());
        }
    }

    private ServerSocketChannel setupServerSocket(Selector selector, int port) throws IOException {
        ServerSocketChannel serverCh = ServerSocketChannel.open();
        serverCh.configureBlocking(false);
        serverCh.socket().setReuseAddress(true);
        serverCh.bind(new InetSocketAddress(port));
        serverCh.register(selector, SelectionKey.OP_ACCEPT);
        return serverCh;
    }
}
