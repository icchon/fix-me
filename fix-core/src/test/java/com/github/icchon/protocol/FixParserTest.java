package com.github.icchon.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

public class FixParserTest {

    @Test
    public void testValidMessage() throws Exception {
        FixParser parser = new FixParser("|");
        // Body: 35=A|49=B|56=M| (15 bytes)
        // 8=FIX.4.2|9=15|35=A|49=B|56=M|
        // Checksum: (sum of above) % 256
        String rawNoChecksum = "8=FIX.4.2|9=15|35=A|49=B|56=M|";
        String checksum = Utils.ChecksumUtils.calculate(rawNoChecksum);
        String raw = rawNoChecksum + "10=" + checksum + "|";
        
        List<FixParser.ParsedData> results = parser.feed(raw);
        assertEquals(1, results.size());
        assertEquals("A", results.get(0).header().msgType());
        assertEquals("B", results.get(0).header().senderID());
        assertEquals("M", results.get(0).header().targetID());
    }

    @Test
    public void testInvalidChecksum() {
        FixParser parser = new FixParser("|");
        String rawNoChecksum = "8=FIX.4.2|9=15|35=A|49=B|56=M|";
        String raw = rawNoChecksum + "10=999|"; // Definitely wrong
        
        FixParser.FixException ex = assertThrows(FixParser.FixException.class, () -> {
            parser.feed(raw);
        });
        assertEquals(FixParser.ParseState.INVALID_CHECKSUM, ex.type);
    }

    @Test
    public void testInvalidFormat() {
        FixParser parser = new FixParser("|");
        // Invalid body length format (not a number)
        String raw = "8=FIX.4.2|9=abc|35=A|";
        
        assertThrows(FixParser.FixException.class, () -> {
            parser.feed(raw);
        });
    }
}
