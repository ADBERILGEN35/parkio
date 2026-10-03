package com.parkio.auth.application.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Java canonical JSON must equal Python's json.dumps(sort_keys=True, separators=(",", ":")). */
class CanonicalJsonTest {

    static Stream<JsonNode> pythonCases() {
        List<JsonNode> cases = new ArrayList<>();
        DurableEvidenceFixtures.json("canonical-json.json").forEach(cases::add);
        return cases.stream();
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("pythonCases")
    void matchesPythonCanonicalBytes(JsonNode testCase) {
        byte[] actual = CanonicalJson.bytes(CanonicalJson.plain(testCase.path("value")));

        assertThat(new String(actual, StandardCharsets.US_ASCII))
                .as(testCase.path("name").asText())
                .isEqualTo(testCase.path("canonical").asText());
    }

    @Test
    void sortsKeysByCodePointNotUtf16Unit() {
        // U+FFFF sorts before U+1F600 by code point; by UTF-16 unit the surrogate 0xD83D comes first.
        assertThat(new String(CanonicalJson.bytes(Map.of("\uffff", 1, "\uD83D\uDE00", 2)), StandardCharsets.US_ASCII))
                .isEqualTo("{\"\\uffff\":1,\"\\ud83d\\ude00\":2}");
    }

    @Test
    void refusesFloatingPointNumbers() {
        assertThatThrownBy(() -> CanonicalJson.bytes(Map.of("x", 1.5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.plain(DurableEvidenceFixtures.JSON.readTree("{\"x\":1.0}")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
