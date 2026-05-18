package com.github.icchon.client;

import com.github.icchon.protocol.FixMessageBuilder;
import com.github.icchon.protocol.FixParser;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Routerとの通信を管理する基底クライアントクラス。
 */
public abstract class Client implements Runnable {
    private String _host;
    private int _port;
    private String _id;
    private String _routerInternalId;
    private String _routerId;
    private Selector _selector;
    private SocketChannel _channel;
    private final Queue<ByteBuffer> _writeQueue = new ConcurrentLinkedQueue<>();
    private final Queue<FixMessageBuilder> _pendingFixMessages = new ConcurrentLinkedQueue<>();
    private final FixParser _parser = new FixParser("|");
    private volatile boolean _isRunning = false;
    private Thread _networkThread;
    private final Object _threadLock = new Object();
    
    protected ExecutorService _messageExecutor;

    private Consumer<String> _rawMessageListener;
    private volatile boolean _isLoggedOn = false;
    private JedisPool _jedisPool;
    private Runnable _onLogonStatusChanged;

    protected final String _idIssuerUrl;
    protected final com.fasterxml.jackson.databind.ObjectMapper _objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    private final java.util.List<Map<String, Object>> _discoveredRouters = new java.util.concurrent.CopyOnWriteArrayList<>();
    private int _currentRouterIndex = 0;

    private volatile boolean _isWaitingToReconnect = false;
    private long _reconnectDelayMs = 2000;

    public Client(String host, int port) {
        this(host, port, null);
    }

    public Client(String host, int port, String id) {
        this(host, port, id, com.github.icchon.protocol.Config.get("ID_ISSUER_URL", "http://localhost:8081"));
    }

    public Client(String host, int port, String id, String idIssuerUrl) {
        this._host = host;
        this._port = port;
        this._id = id;
        this._idIssuerUrl = idIssuerUrl;
    }

    public void setOnLogonStatusChanged(Runnable callback) {
        this._onLogonStatusChanged = callback;
    }

    private void initJedisPool() {
        if (_jedisPool == null || _jedisPool.isClosed()) {
            String redisHost = com.github.icchon.protocol.Config.get("REDIS_HOST", "localhost");
            int redisPort = com.github.icchon.protocol.Config.getInt("REDIS_PORT", 6379);
            this._jedisPool = new JedisPool(redisHost, redisPort);
        }
    }

    private String getSeqKey(String sender, String target, boolean isSend) {
        String cleanSender = normalizeId(sender);
        String cleanTarget = normalizeId(target);
        String type = isSend ? "send" : "recv";
        return "fix:seq:" + type + ":" + cleanSender + ":" + cleanTarget;
    }

    private String normalizeId(String id) {
        return (id == null) ? "unknown" : id;
    }

    protected int getNextSendSeq(String sender, String target) {
        initJedisPool();
        try (Jedis jedis = _jedisPool.getResource()) {
            return (int) jedis.incr(getSeqKey(sender, target, true));
        }
    }

    protected int getExpectedRecvSeq(String sender, String target) {
        initJedisPool();
        try (Jedis jedis = _jedisPool.getResource()) {
            String val = jedis.get(getSeqKey(sender, target, false));
            return (val == null) ? 1 : Integer.parseInt(val);
        }
    }

    protected void setExpectedRecvSeq(String sender, String target, int seq) {
        initJedisPool();
        try (Jedis jedis = _jedisPool.getResource()) {
            jedis.set(getSeqKey(sender, target, false), String.valueOf(seq));
        }
    }

    public void setReconnectDelayMs(long ms) {
        this._reconnectDelayMs = ms;
    }

    @Override
    public void run() {
        try {
            while (_isRunning || !_writeQueue.isEmpty()) {
                try {
                    if (_channel == null || (!_channel.isConnected() && !_channel.isConnectionPending())) {
                        if (!_isRunning) break;

                        if (_isWaitingToReconnect) {
                            System.out.println("Waiting " + (_reconnectDelayMs / 1000) + "s before next discovery attempt...");
                            Thread.sleep(_reconnectDelayMs);
                            _isWaitingToReconnect = false;
                            _discoveredRouters.clear();
                        }

                        if (_discoveredRouters.isEmpty()) {
                            discoverRouters();
                        }

                        if (!_discoveredRouters.isEmpty()) {
                            Map<String, Object> selected = _discoveredRouters.get(_currentRouterIndex);
                            _host = (String) selected.get("host");
                            _port = resolveTargetPort(selected);
                            
                            System.out.println("Attempting to connect to Router candidate [" + (_currentRouterIndex + 1) + "/" + _discoveredRouters.size() + "]: " + selected.get("id") + " at " + _host + ":" + _port);
                            connect();
                        } else {
                            System.err.println("[CLIENT] No routers available from id-issuer. Retrying later...");
                            _isWaitingToReconnect = true;
                            continue;
                        }
                    }

                    if (_channel != null && _channel.isOpen()) {
                        if (!_writeQueue.isEmpty() && _channel.isConnected()) {
                            SelectionKey key = _channel.keyFor(_selector);
                            if (key != null && key.isValid()) {
                                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                            }
                        }
                    }

                    if (_selector.select(1000) == 0) continue;
                    if (!_isRunning && _writeQueue.isEmpty()) break;

                    Set<SelectionKey> selectedKeys = _selector.selectedKeys();
                    Iterator<SelectionKey> iter = selectedKeys.iterator();
                    while (iter.hasNext()) {
                        SelectionKey key = iter.next();
                        iter.remove();
                        if (!key.isValid()) continue;
                        if (key.isConnectable()) handleConnect(key);
                        else if (key.isReadable()) handleRead(key);
                        else if (key.isWritable()) handleWrite(key);
                    }
                } catch (Exception e) {
                    if (_isRunning) {
                        System.err.println("Connection Error: " + e.getMessage());
                        _currentRouterIndex++;
                        if (_currentRouterIndex >= _discoveredRouters.size()) {
                            _isWaitingToReconnect = true;
                            _currentRouterIndex = 0;
                            _port = 0; 
                        } else {
                            _isWaitingToReconnect = false;
                        }
                    }
                    cleanupConnection();
                    if (!_isRunning) break;
                }
            }
        } finally {
            _isWaitingToReconnect = false;
            cleanupConnection();
            synchronized (_threadLock) {
                _isRunning = false;
                _networkThread = null;
            }
            System.out.println("Client network thread terminated.");
        }
    }

    private void discoverRouters() {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(_idIssuerUrl + "/routers"))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                List<Map<String, Object>> routers = _objectMapper.readValue(response.body(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
                if (!routers.isEmpty()) {
                    java.util.Collections.shuffle(routers);
                    _discoveredRouters.clear(); // 新しいリストで上書き
                    _discoveredRouters.addAll(routers);
                    _currentRouterIndex = 0;
                    System.out.println("[DISCOVERY] Found " + _discoveredRouters.size() + " routers from id-issuer.");
                }
            }
        } catch (Exception e) {
            System.err.println("[DISCOVERY] Failed to discover routers: " + e.getMessage());
        }
    }

    protected int resolveTargetPort(Map<String, Object> routerInfo) {
        return (int) routerInfo.get("broker_port");
    }

    public boolean isWaitingToReconnect() { return _isWaitingToReconnect; }

    public boolean isRunning() {
        synchronized (_threadLock) {
            return _networkThread != null && _networkThread.isAlive();
        }
    }

    private void connect() throws IOException {
        if (_selector != null && _selector.isOpen()) _selector.close();
        _selector = Selector.open();
        _channel = SocketChannel.open();
        _channel.configureBlocking(false);
        _channel.register(_selector, SelectionKey.OP_CONNECT);
        _channel.connect(new InetSocketAddress(_host, _port));
    }

    protected void cleanupConnection() {
        _isLoggedOn = false;
        _routerInternalId = null; 
        _writeQueue.clear(); 
        // _pendingFixMessages はフェイルオーバーのために保持する
        try {
            if (_channel != null) { _channel.close(); _channel = null; }
            if (_selector != null) { _selector.close(); _selector = null; }
        } catch (IOException e) { }
    }

    public void start() throws IOException {
        synchronized (_threadLock) {
            if (_networkThread != null && _networkThread.isAlive()) return;
            initJedisPool();
            
            // 初回起動時、IDが未設定なら環境変数やデフォルト値から論理IDを設定
            if (_id == null) {
                _id = com.github.icchon.protocol.Config.get("CLIENT_ID", null);
            }

            if (_messageExecutor == null || _messageExecutor.isShutdown()) {
                _messageExecutor = Executors.newCachedThreadPool();
            }
            _isRunning = true;
            _networkThread = new Thread(this, "Client-Network-Thread");
            _networkThread.start();
        }
    }

    public void stop() {
        _isRunning = false;
        resetSession();
        if (_selector != null) _selector.wakeup();
        if (_messageExecutor != null) _messageExecutor.shutdown();
        if (_jedisPool != null) { _jedisPool.close(); _jedisPool = null; }
    }

    public void forceDisconnect() { stop(); }

    public void resetSession() { _isLoggedOn = false; }

    protected void setLoggedOn(boolean loggedOn) {
        this._isLoggedOn = loggedOn;
        if (_onLogonStatusChanged != null) _onLogonStatusChanged.run();
    }

    public boolean isLoggedOn() { return _isLoggedOn; }

    public void setRawMessageListener(Consumer<String> listener) { this._rawMessageListener = listener; }

    public String getId() { return _id; }
    public void setId(String id) { this._id = id; }
    public String getRouterInternalId() { return _routerInternalId; }
    public void setRouterInternalId(String id) { this._routerInternalId = id; }
    public String getRouterId() { return _routerId; }
    public void setRouterId(String id) { this._routerId = id; }
    public int getPort() { return _port; }
    public boolean isConnected() { return _isRunning && _channel != null && _channel.isOpen() && _channel.isConnected(); }

    protected abstract void onConnected();
    protected abstract void handleMessage(FixParser.ParsedData message);
    
    protected void onRouterIdUpdated() {
        System.out.println("[CLIENT] Router identity confirmed. Assigned local ID: " + _routerInternalId + ". Global Router ID: " + _routerId);
        
        // 保留中のメッセージがあれば送信
        FixMessageBuilder pending;
        while ((pending = _pendingFixMessages.poll()) != null) {
            sendFix(pending);
        }
    }

    public synchronized void send(String message) {
        if (_rawMessageListener != null) _rawMessageListener.accept(">> " + message.trim());
        _writeQueue.add(ByteBuffer.wrap(message.getBytes()));
        if (_selector != null) _selector.wakeup();
    }

    public synchronized void sendFix(FixMessageBuilder builder) {
        if (_routerInternalId == null) {
            _pendingFixMessages.add(builder);
            return;
        }
        
        // FIX メッセージの構築
        String sender = builder.getSenderId();
        String target = builder.getTargetId();
        int nextSeq = getNextSendSeq(sender, target);
        builder.setSeqNum(nextSeq);
        String fixMsg = builder.build();
        
        // [Router割り当てID]|[FIXメッセージ] の形式で送信
        // Routerはこの最初のIDでパケットの正当性を確認し、FIX内部の56番タグでルーティングする
        String msg = _routerInternalId + "|" + fixMsg;
        send(msg);
    }

    private void handleConnect(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        if (channel.isConnectionPending()) channel.finishConnect();
        channel.register(_selector, SelectionKey.OP_READ);
        System.out.println("Connected to Router: " + _host + ":" + _port);
        onConnected();
    }

    private void handleRead(SelectionKey key) throws Exception {
        SocketChannel channel = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        int bytesRead = channel.read(buffer);
        if (bytesRead == -1) throw new IOException("Connection closed by Router.");
        buffer.flip();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        String raw = new String(data);
        if (_rawMessageListener != null) _rawMessageListener.accept("<< " + raw.trim());
        
        String fixPart = raw;

        // 1. Router からの ID 通知の処理 (非FIXメッセージ)
        if (raw.startsWith("ID:")) {
            int delimiterIdx = raw.indexOf('|');
            if (delimiterIdx != -1) {
                String payload = raw.substring(3, delimiterIdx);
                String[] parts = payload.split(":");
                setRouterInternalId(parts[0]);
                if (parts.length > 1) setRouterId(parts[1]);
                onRouterIdUpdated();
                
                if (delimiterIdx + 1 < raw.length()) {
                    fixPart = raw.substring(delimiterIdx + 1);
                } else {
                    return; 
                }
            }
        }

        // 2. FIX メッセージの抽出と処理
        if (fixPart.contains("8=FIX")) {
            int fixIndex = fixPart.indexOf("8=FIX");
            if (fixIndex != -1) fixPart = fixPart.substring(fixIndex);
            
            List<FixParser.ParsedData> messages = _parser.feed(fixPart);
            for (FixParser.ParsedData msg : messages) {
                if (_messageExecutor != null && !_messageExecutor.isShutdown()) {
                    _messageExecutor.submit(() -> {
                        try { validateAndHandle(msg); } catch (Exception e) { }
                    });
                }
            }
        }
    }

    private void validateAndHandle(FixParser.ParsedData msg) {
        String msgType = msg.body().get(35);
        String seqStr = msg.body().get(34);
        int seq = seqStr != null ? Integer.parseInt(seqStr) : -1;
        String sender = msg.header().senderID();
        String target = msg.header().targetID();

        if ("A".equals(msgType)) {
            if (_id == null) _id = target;
            if (seq != -1) setExpectedRecvSeq(target, sender, seq + 1);
            _isLoggedOn = true;
            if (_onLogonStatusChanged != null) _onLogonStatusChanged.run();
            if ("ROUTER".equals(sender)) return; 
        }

        if (seq != -1 && !"A".equals(msgType)) {
            int expected = getExpectedRecvSeq(target, sender);
            if (seq != expected) return;
            setExpectedRecvSeq(target, sender, seq + 1);
        }
        handleMessage(msg);
    }

    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        while (true) {
            ByteBuffer buffer = _writeQueue.peek();
            if (buffer == null) break;
            channel.write(buffer);
            if (buffer.hasRemaining()) break;
            _writeQueue.poll();
        }
        if (_writeQueue.isEmpty()) key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
    }
}
