package com.github.icchon;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class Session{
    public enum SessionState {
        CONNECTED,
        LOGON_RECEIVED,
        ESTABLISHED,
        AWAITING_TEST_RESPONSE,
        LOGOUT_SENT,
        LOGOUT_RECEIVED,
        DISCONNECTED, // Abnormal disconnection
        CLOSED        // Clean logout
    }

    public final SelectionKey key;
    public final String ID;
    private SessionState state = SessionState.CONNECTED;
    private final StringBuilder _writerBuffer = new StringBuilder();
    protected final FixParser _parser = new FixParser("|");

    Session(String id, SelectionKey key){
        this.ID = id;
        this.key = key;
    }

    public SessionState getState() { return state; }
    public void setState(SessionState newState) {
        System.out.printf("[SESSION INFO] ID: %s, State Change: %s -> %s\n", ID, this.state, newState);
        this.state = newState;
    }

    public interface MarketRegistry {
        MarketSession getAvailableMarketSession(String marketId);
    }

    public abstract void processLogon(FixParser.ParsedData data, MarketRegistry registry) throws Exception;
    public abstract void processLogout(FixParser.ParsedData data) throws Exception;
    public abstract void handleMsg(FixParser.ParsedData data) throws Exception;

    public void prepareWrite(String data){
        _writerBuffer.append(data);
        this.key.interestOps(this.key.interestOps() | SelectionKey.OP_WRITE);
    }
    public void doWrite() {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        try {
            String dataToSend = _writerBuffer.toString();
            ByteBuffer writeBuffer = ByteBuffer.wrap(dataToSend.getBytes());
            while (writeBuffer.hasRemaining()) {
                clientChannel.write(writeBuffer);
            }
            _writerBuffer.setLength(0);
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
        } catch (IOException e) {
            handleDisconnection();
        }
    }

    public List<FixParser.ParsedData> doRead() {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ByteBuffer readBuffer = ByteBuffer.allocate(4096);
        try {
            int bytesRead = clientChannel.read(readBuffer);
            if (bytesRead == -1) {
                handleDisconnection();
                return Collections.emptyList();
            }
            readBuffer.flip();
            byte[] data = new byte[readBuffer.remaining()];
            readBuffer.get(data);

            return _parser.feed(new String(data));
        } catch (Exception e) {
            System.err.println("Read/Parse Error for Session " + ID + ": " + e.getMessage());
            handleDisconnection();
            return Collections.emptyList();
        }
    }

    private void handleDisconnection() {
        if (state == SessionState.CLOSED) {
            System.out.println("[SESSION INFO] Clean TCP shutdown for session: " + ID);
        } else {
            this.setState(SessionState.DISCONNECTED);
            System.err.println("[SESSION INFO] Abnormal TCP disconnection for session: " + ID);
        }
        close();
    }

    public void close() {
        try {
            key.cancel();
            key.channel().close();
            System.out.println("Physical connection closed: " + ID + " (Final State: " + state + ")");
        } catch (IOException e) { /* ignore */ }
    }

    public boolean isConnected(){
        return (key != null && key.isValid() && ((SocketChannel)key.channel()).isConnected());
    }
}

