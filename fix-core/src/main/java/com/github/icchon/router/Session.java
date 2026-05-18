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
                final String finalMsg = msg;
                _router.execute(() -> {
                    try {
                        processSingleMessage(finalMsg);
                    } catch (Exception e) {
                        System.err.println("[SESSION ASYNC ERROR] ID: " + ID + " -> " + e.getMessage());
                        close();
                    }
                });
            }
            
        } catch (Exception e) {
            System.err.println("[SESSION ERROR] ID: " + ID + " -> " + e.getMessage());
            e.printStackTrace();
            close();
        }
    }

    private void processSingleMessage(String raw) throws Exception {
        // [SenderID_AssignedByRouter]|[8=FIX...]
        int pipeIdx = raw.indexOf('|');
        if (pipeIdx == -1) return;
        
        String assignedSenderId = raw.substring(0, pipeIdx);
        String fixPayload = raw.substring(pipeIdx + 1);
        
        // 1. チェックサム検証 (要件: Validate the message based on the checksum)
        if (!validateChecksum(fixPayload)) {
            System.err.println("[FIX ERROR] Invalid checksum in message from " + assignedSenderId);
            return;
        }

        // 2. 宛先(56)と送信元(49)の抽出
        String targetID = extractTag(fixPayload, 56);
        String senderID = extractTag(fixPayload, 49);

        if (targetID == null || senderID == null) {
            System.err.println("[ROUTING ERROR] Missing Target(56) or Sender(49) tags");
            return;
        }

        // 3. ルーティングテーブルの学習
        // 送信元ID(49)がこのセッションであることをRouterに学習させる
        _router.registerAlias(senderID, this);

        // 4. 転送処理
        if ("ROUTER".equals(targetID) || "BROADCAST".equals(targetID)) {
            // これらは学習用の宛先なので、転送せずに受理する
            return;
        }
        
        handleMsg(targetID, senderID, fixPayload);
    }

    private String extractTag(String payload, int tag) {
        String search = "|" + tag + "=";
        if (payload.startsWith(tag + "=")) {
            search = tag + "=";
        }
        
        int start = payload.indexOf(search);
        if (start == -1) return null;
        
        start += search.length();
        int end = payload.indexOf('|', start);
        if (end == -1) return null;
        
        return payload.substring(start, end);
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

