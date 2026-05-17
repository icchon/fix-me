package com.github.icchon.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ConfigTest {

    @Test
    public void testGetDefault() {
        assertEquals("default", Config.get("NON_EXISTENT_KEY", "default"));
    }

    @Test
    public void testGetIntDefault() {
        assertEquals(8080, Config.getInt("NON_EXISTENT_PORT", 8080));
    }

    @Test
    public void testGetFromSystemProperty() {
        System.setProperty("TEST_KEY", "test_value");
        assertEquals("test_value", Config.get("TEST_KEY", "default"));
        System.clearProperty("TEST_KEY");
    }

    @Test
    public void testGetIntFromSystemProperty() {
        System.setProperty("TEST_PORT", "9090");
        assertEquals(9090, Config.getInt("TEST_PORT", 8080));
        System.clearProperty("TEST_PORT");
    }
}
