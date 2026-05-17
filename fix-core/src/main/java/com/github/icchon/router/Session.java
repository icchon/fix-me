package com.github.icchon.router;

import com.github.icchon.protocol.Utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public abstract class Session {
    private SelectionKey key;
    public final String ID;
    protected final Router _router;
    private final Queue<ByteBuffer> _writeQueue = new ConcurrentLinkedQueue<>();
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

    public abstract void handleMsg(String targetId, String senderId, String payload) throws Exception;

    public void prepareWrite(String data) {
        System.out.println("[RAW SEND] ID: " + ID + " -> " + data.trim());
        _writeQueue.add(ByteBuffer.wrap(data.getBytes()));
        
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
                if (buffer.hasRemaining()) break; 
                _writeQueue.poll();
            }

            if (_writeQueue.isEmpty()) {
                key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            }
        } catch (IOException e) {
            close();
        }
    }

    public void doRead() {
        if (!isOpen()) return;
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ByteBuffer readBuffer = ByteBuffer.allocate(8192);
        try {
            int bytesRead = clientChannel.read(readBuffer);
            if (bytesRead == -1) {
                System.out.println("[SESSION] Connection closed by client: " + ID);
                close();
                return;
            }
            readBuffer.flip();
            byte[] data = new byte[readBuffer.remaining()];
            readBuffer.get(data);
            String raw = new String(data);
            System.out.println("[RAW RECEIVE] ID: " + ID + " -> " + raw.trim());

            String[] messages = raw.split("(?<=\\|10=\\d{3}\\|)");
            for (String msg : messages) {
                if (msg.trim().isEmpty()) continue;
                processSingleMessage(msg);
            }
            
        } catch (Exception e) {
            System.err.println("[SESSION ERROR] ID: " + ID + " -> " + e.getMessage());
            e.printStackTrace();
            close();
        }
    }

    private void processSingleMessage(String raw) throws Exception {
        if (raw.contains("HELLO:")) {
            int firstPipe = raw.indexOf("|");
            String payload = raw.substring(firstPipe + 1);
            int nextPipe = payload.indexOf('|');
            String helloMsg = (nextPipe != -1) ? payload.substring(0, nextPipe) : payload;
            String marketName = helloMsg.substring(6);
            System.out.println("[HANDSHAKE] Received HELLO from Market: " + marketName);
            _router.registerAlias(marketName, this);
            _router.registerMarketWithIssuer(marketName, ID);
            return;
        }

        // Expected format: AssignedID|TargetID|SenderID|8=FIX...
        String[] parts = raw.split("\\|", 4);
        if (parts.length < 4) {
            System.err.println("[ROUTING ERROR] Malformed message wrapper: " + raw);
            return;
        }

        String targetId = parts[1];
        String senderId = parts[2];
        String fixPayload = parts[3];

        if (!validateChecksum(fixPayload)) {
            System.err.println("[FIX ERROR] Invalid checksum for message from " + senderId);
            return;
        }

        handleMsg(targetId, senderId, fixPayload);
    }

    private boolean validateChecksum(String payload) {
        int checksumTagPos = payload.lastIndexOf("10=");
        if (checksumTagPos == -1) return false;
        
        String dataToCalc = payload.substring(0, checksumTagPos);
        String expected = Utils.ChecksumUtils.calculate(dataToCalc);
        
        int endPos = payload.indexOf('|', checksumTagPos);
        if (endPos == -1) return false;
        
        String actual = payload.substring(checksumTagPos + 3, endPos);
        return expected.equals(actual);
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

