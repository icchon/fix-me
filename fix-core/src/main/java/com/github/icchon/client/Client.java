package com.github.icchon.client;

import com.github.icchon.protocol.FixMessageBuilder;
import com.github.icchon.protocol.FixParser;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Routerとの通信を管理する基底クライアントクラス。
 * シーケンス番号管理、ハートビート、Rawログ出力をサポート。
 */
public abstract class Client implements Runnable {
    private final String _host;
    private final int _port;
    private String _id;
    private Selector _selector;
    private SocketChannel _channel;
    private final Queue<ByteBuffer> _writeQueue = new ConcurrentLinkedQueue<>();
    private final FixParser _parser = new FixParser("|");
    private volatile boolean _isRunning = false;

    // Phase 3: シーケンス番号とハートビート
    private int _sendSeqNum = 1;
    private int _expectedRecvSeqNum = 1;
    private long _lastSentTime = System.currentTimeMillis();
    private long _lastRecvTime = System.currentTimeMillis();
    private static final long HEARTBEAT_INTERVAL = 30000; // 30秒

    public Client(String host, int port) {
        this._host = host;
        this._port = port;
    }

    public void start() throws IOException {
        _selector = Selector.open();
        _channel = SocketChannel.open();
        _channel.configureBlocking(false);
        _channel.register(_selector, SelectionKey.OP_CONNECT);
        _channel.connect(new InetSocketAddress(_host, _port));
        _isRunning = true;
        new Thread(this, "Client-Network-Thread").start();
    }

    public void stop() {
        _isRunning = false;
        if (_selector != null) _selector.wakeup();
    }

    public String getId() {
        return _id;
    }

    protected abstract void onConnected();
    protected abstract void handleMessage(FixParser.ParsedData message);

    /**
     * メッセージを送信し、送信シーケンス番号を更新する。
     */
    public synchronized void send(String message) {
        // IDがまだない場合はそのまま送る（ID受信待ちなど）
        // FIXメッセージの場合は、ここに到達する前にBuilderでTag 34がセットされている想定だが、
        // 基底クラスでもRawログを表示するようにする。
        System.out.println("[RAW SEND] " + message.trim());
        _writeQueue.add(ByteBuffer.wrap(message.getBytes()));
        _lastSentTime = System.currentTimeMillis();
        if (_selector != null) {
            _selector.wakeup();
        }
    }

    /**
     * FIXメッセージを送信する（シーケンス番号を自動付与）。
     */
    public void sendFix(FixMessageBuilder builder) {
        builder.setSeqNum(_sendSeqNum++);
        send(builder.build());
    }

    @Override
    public void run() {
        try {
            while (_isRunning) {
                // ハートビートが必要かチェック
                long now = System.currentTimeMillis();
                long timeout = HEARTBEAT_INTERVAL - (now - _lastSentTime);
                if (timeout <= 0) {
                    sendHeartbeat();
                    timeout = HEARTBEAT_INTERVAL;
                }

                _selector.select(timeout);
                if (!_isRunning) break;

                if (!_writeQueue.isEmpty()) {
                    SelectionKey key = _channel.keyFor(_selector);
                    if (key != null && key.isValid()) {
                        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                    }
                }

                Set<SelectionKey> selectedKeys = _selector.selectedKeys();
                Iterator<SelectionKey> iter = selectedKeys.iterator();

                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();

                    if (!key.isValid()) continue;

                    if (key.isConnectable()) {
                        handleConnect(key);
                    } else if (key.isReadable()) {
                        handleRead(key);
                    } else if (key.isWritable()) {
                        handleWrite(key);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Client Network Error: " + e.getMessage());
        } finally {
            close();
        }
    }

    private void sendHeartbeat() {
        if (_id != null) {
            FixMessageBuilder hb = FixMessageBuilder.start(_id, "|")
                    .setMsgType("0") // Heartbeat
                    .setField(49, _id);
            // TargetIDは不明だが、RouterがTag 56を要求する場合は適切な値をセットする必要がある
            // ここではSenderのみセットして送信
            sendFix(hb);
        }
    }

    private void handleConnect(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        if (channel.isConnectionPending()) {
            channel.finishConnect();
        }
        channel.register(_selector, SelectionKey.OP_READ);
        System.out.println("Connected to Router: " + _host + ":" + _port);
    }

    private void handleRead(SelectionKey key) throws Exception {
        SocketChannel channel = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        int bytesRead = channel.read(buffer);

        if (bytesRead == -1) {
            System.out.println("Connection closed by Router.");
            close();
            return;
        }

        buffer.flip();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        String raw = new String(data);
        System.out.println("[RAW RECEIVE] " + raw.trim());
        _lastRecvTime = System.currentTimeMillis();

        if (_id == null) {
            String[] parts = raw.split("\n", 2);
            _id = parts[0].trim();
            System.out.println("Assigned ID: " + _id);
            onConnected();
            
            if (parts.length > 1 && !parts[1].isEmpty()) {
                raw = parts[1];
            } else {
                return;
            }
        }

        List<FixParser.ParsedData> messages = _parser.feed(raw);
        for (FixParser.ParsedData msg : messages) {
            validateAndHandle(msg);
        }
    }

    private void validateAndHandle(FixParser.ParsedData msg) {
        String msgType = msg.body().get(35);
        String seqStr = msg.body().get(34);
        int seq = seqStr != null ? Integer.parseInt(seqStr) : -1;

        // Logon受信時は、その番号で同期を図る
        if ("A".equals(msgType)) {
            System.out.println("[LOGON] Session Established. Synchronizing sequence to " + seq);
            _expectedRecvSeqNum = seq;
        }

        // シーケンス番号のチェック
        if (seq != -1) {
            if (seq != _expectedRecvSeqNum) {
                System.err.println("[SEQ ERROR] Expected: " + _expectedRecvSeqNum + ", Got: " + seq + " (MsgType: " + msgType + ")");
            }
            _expectedRecvSeqNum = seq + 1;
        }

        if ("0".equals(msgType)) {
            System.out.println("[HEARTBEAT] Received");
        } else if ("A".equals(msgType)) {
            // すでに上で処理済み
        } else {
            handleMessage(msg);
        }
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

        if (_writeQueue.isEmpty()) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
        }
    }

    private void close() {
        try {
            _isRunning = false;
            if (_channel != null) _channel.close();
            if (_selector != null) _selector.close();
        } catch (IOException e) { /* ignore */ }
    }
}
