package com.github.icchon.router;

import com.github.icchon.protocol.FixMessageBuilder;
import com.github.icchon.protocol.FixParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public abstract class Session {
    private SelectionKey key;
    public final String ID;
    protected final Router _router;
    private final Queue<ByteBuffer> _writeQueue = new ConcurrentLinkedQueue<>();
    protected final FixParser _parser = new FixParser("|");
    private Runnable _onClose;
    private boolean _authenticated = false;

    Session(String id, SelectionKey key, Router router) {
        this.ID = id;
        this.key = key;
        this._router = router;
    }

    public void setKey(SelectionKey key) {
        this.key = key;
    }

    public boolean isAuthenticated() {
        return _authenticated;
    }

    public void setAuthenticated(boolean authenticated) {
        this._authenticated = authenticated;
    }

    public void setOnClose(Runnable onClose) {
        this._onClose = onClose;
    }

    public abstract void handleMsg(FixParser.ParsedData data) throws Exception;

    public void prepareWrite(String data) {
        System.out.println("[RAW SEND] ID: " + ID + " -> " + data.trim());
        _writeQueue.add(ByteBuffer.wrap(data.getBytes()));
        
        // SelectorスレッドでinterestOpsを変更させる
        _router.submitTask(() -> {
            SelectionKey k = this.key;
            if (k != null && k.isValid()) {
                k.interestOps(k.interestOps() | SelectionKey.OP_WRITE);
            }
        });
    }

    public boolean isOpen() {
        return key != null && key.isValid() && key.channel().isOpen();
    }

    public void doWrite() {
        if (!isOpen()) return;
        SocketChannel clientChannel = (SocketChannel) key.channel();
        try {
            while (true) {
                ByteBuffer buffer = _writeQueue.peek();
                if (buffer == null) break;

                clientChannel.write(buffer);
                if (buffer.hasRemaining()) break; // Socket buffer full
                _writeQueue.poll();
            }

            if (_writeQueue.isEmpty()) {
                key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            }
        } catch (IOException e) {
            close();
        }
    }


    public List<FixParser.ParsedData> doRead() {
        if (!isOpen()) return Collections.emptyList();
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ByteBuffer readBuffer = ByteBuffer.allocate(4096);
        try {
            int bytesRead = clientChannel.read(readBuffer);
            if (bytesRead == -1) {
                System.out.println("[SESSION] Connection closed by client: " + ID);
                close();
                return Collections.emptyList();
            }
            readBuffer.flip();
            byte[] data = new byte[readBuffer.remaining()];
            readBuffer.get(data);
            String raw = new String(data);
            System.out.println("[RAW RECEIVE] ID: " + ID + " -> " + raw.trim());

            // Requirement: All messages will start with the ID assigned by the router
            // ID|8=FIX.4.2|...|10=...|
            // またはハンドシェイク用
            // ID|HELLO:market-A|
            String payload = raw;
            if (raw.contains("|")) {
                int firstPipe = raw.indexOf("|");
                payload = raw.substring(firstPipe + 1);
            }

            if (payload.startsWith("HELLO:")) {
                int nextPipe = payload.indexOf('|');
                String helloMsg = (nextPipe != -1) ? payload.substring(0, nextPipe) : payload;
                String marketName = helloMsg.substring(6);
                System.out.println("[HANDSHAKE] Received HELLO from Market: " + marketName);
                _router.registerAlias(marketName, this);
                _router.registerMarketWithIssuer(marketName, ID);
                return Collections.emptyList();
            }

            return _parser.feed(payload);
        } catch (FixParser.FixException e) {
            System.err.println("FIX Protocol Error: " + e.getMessage());
            sendReject(e);
            close();
            return Collections.emptyList();
        } catch (Exception e) {
            System.err.println("[SESSION ERROR] ID: " + ID + " -> " + e.getMessage());
            e.printStackTrace();
            close();
            return Collections.emptyList();
        }
    }

    private void sendReject(FixParser.FixException e) {
        String reason = "99"; // Other
        if (e.type == FixParser.ParseState.INVALID_CHECKSUM) reason = "9"; // Invalid Checksum
        if (e.type == FixParser.ParseState.INVALID_FORMAT) reason = "11"; // Invalid Tag Number (or format)

        FixMessageBuilder reject = FixMessageBuilder.start(ID, "|")
                .setField(49, "ROUTER")
                .setMsgType("3") // Reject
                .setField(373, reason)
                .setField(58, e.getMessage());

        prepareWrite(reject.build());
    }

    public void close() {
        if (_onClose != null) _onClose.run();
        try {
            if (key != null) {
                key.cancel();
                if (key.channel() != null) {
                    key.channel().close();
                }
            }
            System.out.println("Connection closed: " + ID);
        } catch (IOException e) {
            /* ignore */
        }
    }

    public boolean isConnected() {
        return (key != null && key.isValid() && ((SocketChannel) key.channel()).isConnected());
    }
}
