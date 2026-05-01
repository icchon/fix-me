package com.github.icchon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class FixParser {
    private ParseState state = ParseState.WAITING_FOR_8;
    private final StringBuilder buffer = new StringBuilder();
    private final String delimiter;

    private int bodyLength = -1;
    private int expectedTotalLength = -1;

    public FixParser(String delimiter) {
        this.delimiter = delimiter;
    }

    public enum ParseState {
        WAITING_FOR_8,
        READING_8_VALUE,
        READING_9_VALUE,
        READING_BODY,
        READING_10_VALUE,
        COMPLETED,
        INVALID_FORMAT,
        INVALID_CHECKSUM,
        TOO_LARGE
    }

    public record ParsedData(
            String targetSessionID,
            Header header,
            Body body,
            Trailer trailer,
            String fixPayload
    ) {}

    public record Header(String beginString, int bodyLength, String msgType, String senderID, String targetID) {}
    public record Body(Map<Integer, String> fields) {
        public String get(int tag) { return fields.get(tag); }
    }
    public record Trailer(String checksum) {}

    /**
     * TCPから受信したデータをバッファに追加し、完了したメッセージをすべて返す
     */
    public List<ParsedData> feed(String data) throws Exception {
        buffer.append(data);
        List<ParsedData> results = new ArrayList<>();

        while (true) {
            switch (state) {
                case WAITING_FOR_8 -> {
                    int idx = buffer.indexOf("8=");
                    if (idx == -1) return results;

                    buffer.delete(0, idx); // ゴミを掃除
                    state = ParseState.READING_8_VALUE;
                }

                case READING_8_VALUE -> {
                    int delimIdx = buffer.indexOf(delimiter);
                    if (delimIdx == -1) return results;
                    if (buffer.indexOf("9=", delimIdx) == -1 && buffer.length() > delimIdx + 2) {
                        state = ParseState.INVALID_FORMAT;
                    } else {
                        state = ParseState.READING_9_VALUE;
                    }
                }

                case READING_9_VALUE -> {
                    int tag9Idx = buffer.indexOf("9=");
                    int delimIdx = buffer.indexOf(delimiter, tag9Idx);
                    if (delimIdx == -1) return results;

                    try {
                        String lengthStr = buffer.substring(tag9Idx + 2, delimIdx);
                        this.bodyLength = Integer.parseInt(lengthStr);

                        int startOfBody = delimIdx + 1;

                        this.expectedTotalLength = startOfBody + bodyLength + 7;

                        state = ParseState.READING_BODY;
                    } catch (NumberFormatException e) {
                        state = ParseState.INVALID_FORMAT;
                    }
                }

                case READING_BODY -> {
                    if (buffer.length() < expectedTotalLength) return results;
                    int finalDelimIdx = buffer.indexOf(delimiter, expectedTotalLength - 1);
                    if (finalDelimIdx == -1) {
                        return results;
                    }

                    this.expectedTotalLength = finalDelimIdx + 1;
                    state = ParseState.READING_10_VALUE;
                }

                case READING_10_VALUE -> {
                    String payload = buffer.substring(0, expectedTotalLength);

                    int checksumTagPos = payload.lastIndexOf("10=");
                    if (checksumTagPos == -1) {
                        state = ParseState.INVALID_FORMAT;
                        break;
                    }

                    String dataToCalc = payload.substring(0, checksumTagPos);
                    String expected = Utils.ChecksumUtils.calculate(dataToCalc);

                    String actual = payload.substring(checksumTagPos + 3, payload.length() - 1);

                    if (!expected.equals(actual)) {
                        System.err.printf("Checksum Error! Exp:%s, Act:%s\n", expected, actual);
                        buffer.delete(0, expectedTotalLength);
                        state = ParseState.WAITING_FOR_8;
                    } else {
                        results.add(finalizeParse(payload));
                        buffer.delete(0, expectedTotalLength);
                        state = ParseState.WAITING_FOR_8;
                    }
                }

                case INVALID_FORMAT, INVALID_CHECKSUM, TOO_LARGE -> {
                    buffer.delete(0, Math.min(buffer.length(), 2));
                    ParseState tmp = state;
                    state = ParseState.WAITING_FOR_8;
                    throw new Exception("FIX Parsing Error: " + tmp);
                }
            }
        }
    }

    private ParsedData finalizeParse(String payload) {
        Map<Integer, String> fields = new HashMap<>();
        String[] pairs = payload.split(Pattern.quote(delimiter));

        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                fields.put(Integer.parseInt(pair.substring(0, eq)), pair.substring(eq + 1));
            }
        }

        Header header = new Header(
                fields.get(8),
                bodyLength,
                fields.get(35),
                fields.get(49),
                fields.get(56)
        );

        return new ParsedData(
                header.targetID(),
                header,
                new Body(fields),
                new Trailer(fields.get(10)),
                payload
        );
    }
}
