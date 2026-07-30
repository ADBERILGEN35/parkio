package com.parkio.parking.externalsource.osm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OsmExternalIdTest {
    @Test void preventsNodeWayRelationCollisions() {
        assertThat(OsmExternalId.of(OsmElementType.NODE, 1001)).isEqualTo("node/1001");
        assertThat(OsmExternalId.of(OsmElementType.WAY, 1001)).isEqualTo("way/1001");
        assertThat(OsmExternalId.of(OsmElementType.RELATION, 1001)).isEqualTo("relation/1001");
        assertThat(OsmExternalId.of(OsmElementType.NODE, 1001))
                .isNotEqualTo(OsmExternalId.of(OsmElementType.WAY, 1001));
    }

    @Test void rejectsNonPositiveIds() {
        assertThatThrownBy(() -> OsmExternalId.of(OsmElementType.NODE, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}