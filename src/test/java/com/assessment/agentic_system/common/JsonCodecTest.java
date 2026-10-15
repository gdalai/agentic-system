package com.assessment.agentic_system.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonCodecTest {

    @Test
    void roundTripsSpecialCharacters() {
        Map<String, String> original = Map.of(
                "plain", "value",
                "quote", "say \"hi\"",
                "slash", "a\\b",
                "lines", "one\ntwo\tthree",
                "unicode", "café ✓");

        assertThat(JsonCodec.decode(JsonCodec.encode(original))).isEqualTo(original);
    }

    @Test
    void encodesKeysInSortedOrder() {
        assertThat(JsonCodec.encode(Map.of("b", "2", "a", "1"))).isEqualTo("{\"a\":\"1\",\"b\":\"2\"}");
    }

    @Test
    void handlesEmptyAndNull() {
        assertThat(JsonCodec.encode(Map.of())).isEqualTo("{}");
        assertThat(JsonCodec.decode("{}")).isEmpty();
        assertThat(JsonCodec.decode(null)).isEmpty();

        Map<String, String> withNull = new HashMap<>();
        withNull.put("k", null);
        assertThat(JsonCodec.decode(JsonCodec.encode(withNull))).containsEntry("k", null);
    }

    @Test
    void rejectsMalformedInput() {
        assertThatThrownBy(() -> JsonCodec.decode("{\"a\":1}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonCodec.decode("{\"a\":\"x\"")).isInstanceOf(IllegalArgumentException.class);
    }
}
