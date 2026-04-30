package com.github.icchon;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class Session{
    public final SelectionKey key;
    public final String ID;
    private final StringBuilder _writerBuffer = new StringBuilder();
    protected final FixParser _parser = new FixParser("|");

    Session(String id, SelectionKey key){
        this.ID = id;
        this.key = key;
    }
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
            close();
        }
    }

    public List<FixParser.ParsedData> doRead() {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ByteBuffer readBuffer = ByteBuffer.allocate(4096);
        try {
            int bytesRead = clientChannel.read(readBuffer);
            if (bytesRead == -1) {
                close();
                return Collections.emptyList();
            }
            readBuffer.flip();
            byte[] data = new byte[readBuffer.remaining()];
            readBuffer.get(data);

            return _parser.feed(new String(data));
        } catch (Exception e) {
            System.err.println("Read/Parse Error for Session " + ID + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    public void close() {
        try {
            key.cancel();
            key.channel().close();
            System.out.println("Session closed: " + ID);
        } catch (IOException e) { /* ignore */ }
    }

    public boolean isConnected(){
        return (key != null && key.isValid() && ((SocketChannel)key.channel()).isConnected());
    }
}

