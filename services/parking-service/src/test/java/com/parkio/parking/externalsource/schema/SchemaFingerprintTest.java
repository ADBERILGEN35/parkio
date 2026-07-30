package com.parkio.parking.externalsource.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class SchemaFingerprintTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fingerprintsSortedTopLevelKeysDeterministically() {
        ObjectNode first = mapper.createObjectNode();
        first.put("ufid", "a");
        first.put("lat", 1);
        first.put("lng", 2);
        first.set("occupancy", mapper.createObjectNode());
        ArrayNode payload = mapper.createArrayNode().add(first);

        SchemaFingerprint one = SchemaFingerprint.fromArray(payload);
        SchemaFingerprint two = SchemaFingerprint.fromArray(payload);

        assertThat(one.value()).isEqualTo(two.value());
        assertThat(one.fields()).containsExactly("lat", "lng", "occupancy", "ufid");
    }

    @Test
    void rejectsEmptyOrNonArrayPayload() {
        assertThatThrownBy(() -> SchemaFingerprint.fromArray(mapper.createArrayNode()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SchemaFingerprint.fromArray(mapper.createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
