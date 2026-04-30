package com.github.icchon;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class FixMessageBuilder {
    private final String _delimiter;
    private final String _idPrefix;
    private final StringBuilder _body = new StringBuilder();

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss");

    private FixMessageBuilder(String idPrefix, String delimiter) {
        this._idPrefix = idPrefix;
        this._delimiter = delimiter;
    }

    public static FixMessageBuilder start(String idPrefix, String delimiter) {
        return new FixMessageBuilder(idPrefix, delimiter);
    }

    public FixMessageBuilder setMsgType(String type) {
        return setField(35, type);
    }

    public FixMessageBuilder setField(int tag, String value) {
        _body.append(tag).append("=").append(value).append(_delimiter);
        return this;
    }

    public String build() {
        if (!_body.toString().contains("52=")) {
            setField(52, LocalDateTime.now().format(TIME_FORMAT));
        }

        StringBuilder header = new StringBuilder();
        header.append("8=FIX.4.2").append(_delimiter);

        int bodyLength = _body.length();
        header.append("9=").append(bodyLength).append(_delimiter);

        String preChecksumMsg = _idPrefix + _delimiter + header.toString() + _body.toString();

        String checksum = Utils.ChecksumUtils.calculate(preChecksumMsg);
        return preChecksumMsg + "10=" + checksum + _delimiter + "\n";
    }
}
